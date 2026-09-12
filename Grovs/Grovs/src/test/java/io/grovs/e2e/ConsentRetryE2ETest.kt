package io.grovs.e2e

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import io.grovs.Grovs
import io.grovs.handlers.ConsentController
import io.grovs.handlers.GrovsContext
import io.grovs.handlers.GrovsManager
import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel
import io.grovs.service.ConsentBackend
import io.grovs.service.DrivenDispatcher
import io.grovs.service.GatedExecutor
import io.grovs.service.GrovsService
import io.grovs.service.useConsentController
import io.grovs.viewmodels.NotificationsMainViewModel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * E04: the retrying families through the public SDK over real HTTP. A genuine transport failure;
 * proof of the first request and of its failure reaching the SDK; consent withdrawn during the
 * retry wait; several retry intervals with no further old request; then the server recovers and
 * consent is granted again, and only explicitly allowed new or resumed operations run.
 *
 * Time: the SDK's serial dispatcher is a [DrivenDispatcher] (a StandardTestDispatcher that also
 * records cross-thread dispatches), installed through the existing GrovsContext(serialDispatcher)
 * seam, so retry waits are virtual. The notification view model's retry wait is on Robolectric's
 * paused main looper, whose clock the test advances. Real HTTP is awaited with bounded polls; no
 * test sleeps through a real 5 s retry interval.
 *
 * "In the retry wait" is established from the SDK's own INFO log line for the failed attempt,
 * which is written synchronously right before the wait starts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConsentRetryE2ETest {

    private lateinit var application: Application
    private lateinit var backend: ConsentBackend
    private lateinit var driver: DrivenDispatcher
    private lateinit var context: GrovsContext
    private var gate: GatedExecutor? = null

    /** Comfortably past the whole retry budget: 2s + 4s + 8s of waits. */
    private val intervals = 60_000L

    private val device = "device_for_vendor_id"
    private val authenticate = "authenticate"
    private val payload = "data_for_device_and_url"
    private val attributes = "visitor_attributes"
    private val unread = "number_of_unread_notifications"
    private val notifications = "notifications_for_device"

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        E2ETestUtils.resetGrovsSingleton()
        E2ETestUtils.setupTestApplication(application)
        E2ETestUtils.setupMockGlInfo()
        E2ETestUtils.setupMockUserAgent("Grovs SDK consent retry tests")
        E2ETestUtils.setupMockScreenResolution()
        DebugLogger.instance.logLevel = LogLevel.INFO
        backend = ConsentBackend().apply {
            phase = "enabled"
            respond(device, """{"last_seen":null}""")
            respond(authenticate, """{"linksquared":"test-grovs-id","uri_scheme":"testapp"}""")
            respond("notifications_to_display_automatically", """{"notifications":[]}""")
            respond("events/batch", """{"accepted":50,"rejected":0,"errors":[]}""")
            respond("clipboard_status", """{"clipboard_active":false}""")
        }
        driver = DrivenDispatcher()
        context = GrovsContext(serialDispatcher = driver)
        E2ETestUtils.installGrovsContext(context)
    }

    @After
    fun tearDown() {
        gate?.open()
        // Retire while driving the SDK dispatcher, so registered work can finish cancelling.
        val retirement = context.consent.retireConfiguration(enabled = false)
        driver.runUntil("the configuration's cleanup", onEachPass = { idleMain() }) { retirement.cleanup.isCompleted }
        E2ETestUtils.resetGrovsSingleton()
        DebugLogger.instance.logLevel = LogLevel.ERROR
        backend.close()
        assertEquals("requests to unrouted paths", emptyList<String>(), backend.unexpected.toList())
    }

    // ==================== Harness ====================

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun pump() {
        driver.runCurrent()
        idleMain()
        driver.runCurrent()
    }

    private fun pumpUntil(description: String, condition: () -> Boolean) =
        driver.runUntil(description, onEachPass = { idleMain(); driver.runCurrent() }, condition = condition)

    private fun manager() = E2ETestUtils.getGrovsManager() as GrovsManager

    private fun logged(text: String) = ShadowLog.getLogs().any { text in it.msg }

    /** Revocation cancellations wait until teardown, so only token validation can stop a stale retry. */
    private fun holdCleanup() {
        gate = GatedExecutor().also { context.useConsentController(ConsentController(cleanupExecutor = it)) }
    }

    private fun configure() = Grovs.configure(
        application, "test-api-key", useTestEnvironment = true, baseURL = backend.baseUrl,
        autoTrackScreenViews = false, clipboardDomains = null, enabled = true,
    )

    private fun authenticated() {
        configure()
        pumpUntil("authentication") { manager().authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED }
    }

    /** Starts the family's request, which fails at the transport, and returns once it is in its retry wait. */
    private fun failIntoRetryWait(path: String, failureLog: String, start: () -> Unit) {
        backend.fail(path)
        start()
        pumpUntil("the first $path request to fail into its retry wait") { backend.count(path) >= 1 && logged(failureLog) }
        assertTrue("the $path retry wait is a registered operation", context.consent.registrationCount() >= 1)
    }

    /**
     * Withdraws consent during the retry wait. Revocation itself must end the wait (no retry
     * interval passes); then several intervals pass with no further request.
     */
    private fun revokeDuringWait(path: String): Int {
        val attempts = backend.count(path)
        val before = driver.currentTime
        Grovs.setSDK(false)
        backend.phase = "disabled"
        val cleanup = context.consent.pendingWork()
        pumpUntil("the revoked $path work to finish") { cleanup.isCompleted }
        assertEquals("revocation ended the wait without a retry interval passing", before, driver.currentTime)
        driver.advanceTimeBy(intervals)
        pump()
        backend.assertQuiet(path, attempts)
        return attempts
    }

    private fun grantAgainAfterRecovery(path: String, recoveredBody: String) {
        backend.respond(path, recoveredBody)
        Grovs.setSDK(true)
        backend.phase = "re-enabled"
        pump()
        driver.advanceTimeBy(intervals)
        pump()
    }

    // ==================== Authentication families ====================

    @Test
    fun `E04 device lookup - disabled during the retry wait, a new grant authenticates once`() {
        backend.fail(device)
        configure()
        pumpUntil("the device lookup to retry") { manager().authenticationState == GrovsManager.AuthenticationState.RETRYING }
        assertTrue(context.consent.registrationCount() >= 1)

        revokeDuringWait(device)
        assertEquals("no authentication while disabled", 0, backend.count(authenticate))

        backend.respond(device, """{"last_seen":null}""")
        Grovs.setSDK(true)
        backend.phase = "re-enabled"
        pumpUntil("authentication after the new grant") {
            manager().authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED
        }
        driver.advanceTimeBy(intervals)
        pump()
        assertEquals("one new device lookup, no stale retry", 1, backend.count(device, "re-enabled"))
        assertEquals(1, backend.count(authenticate, "re-enabled"))
    }

    @Test
    fun `E04 authentication - disabled during the retry wait, a new grant authenticates once`() {
        backend.fail(authenticate)
        configure()
        pumpUntil("authentication to retry") {
            backend.count(authenticate) >= 1 && manager().authenticationState == GrovsManager.AuthenticationState.RETRYING
        }
        assertTrue(context.consent.registrationCount() >= 1)

        revokeDuringWait(authenticate)

        backend.respond(authenticate, """{"linksquared":"test-grovs-id","uri_scheme":"testapp"}""")
        Grovs.setSDK(true)
        backend.phase = "re-enabled"
        pumpUntil("authentication after the new grant") {
            manager().authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED
        }
        driver.advanceTimeBy(intervals)
        pump()
        assertEquals(1, backend.count(device, "re-enabled"))
        assertEquals("one new authentication, no stale retry", 1, backend.count(authenticate, "re-enabled"))
    }

    // ==================== Payload family ====================

    @Test
    fun `E04 link lookup - disabled during the retry wait, consent alone never replays it`() {
        authenticated()
        val link = "https://demo.sqd.link/consent-retry"
        val activity = Robolectric.buildActivity(TestActivity::class.java, Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        failIntoRetryWait(payload, "Fetching payload - Failed") { activity.create().start() }
        assertEquals(link, JSONObject(backend.seen.first { it.path == payload }.body).getString("url"))

        revokeDuringWait(payload)
        grantAgainAfterRecovery(payload, """{"link":null,"data":null}""")
        assertEquals("consent alone never replays the lookup", 0, backend.count(payload, "re-enabled"))

        // Explicitly allowed: a new intent the host forwards starts one new lookup.
        backend.phase = "explicit"
        val next = "https://demo.sqd.link/consent-retry-next"
        Grovs.onNewIntent(Intent(Intent.ACTION_VIEW, Uri.parse(next)), activity.get())
        pumpUntil("the explicit lookup") { backend.count(payload, "explicit") == 1 }
        driver.advanceTimeBy(intervals)
        pump()
        assertEquals(1, backend.count(payload, "explicit"))
        assertEquals(next, JSONObject(backend.seen.last { it.path == payload }.body).getString("url"))
    }

    // ==================== Attributes family ====================

    @Test
    fun `E04 attribute update - disabled during the retry wait, the old update never resumes`() {
        authenticated()
        failIntoRetryWait(attributes, "Set attributes - Failed") { Grovs.pushToken = "push-1" }
        assertEquals("push-1", JSONObject(backend.seen.first { it.path == attributes }.body).getString("push_token"))

        val attempts = revokeDuringWait(attributes)
        grantAgainAfterRecovery(attributes, "{}")
        // A new grant sends the latest desired state in a new operation. Wait for that request
        // before changing phases, so its response cannot be mistaken for the explicit push-2 call.
        pumpUntil("the desired state to be resent under the new grant") {
            backend.count(attributes, "re-enabled") >= 1
        }
        assertEquals(1, backend.count(attributes, "re-enabled"))
        assertEquals("push-1", JSONObject(backend.seen.single {
            it.path == attributes && it.phase == "re-enabled"
        }.body).getString("push_token"))
        backend.assertQuiet(attributes, attempts + 1)

        backend.phase = "explicit"
        Grovs.pushToken = "push-2"
        pumpUntil("the explicit update") {
            backend.seen.any { it.path == attributes && JSONObject(it.body).optString("push_token") == "push-2" }
        }
        driver.advanceTimeBy(intervals)
        pump()
        assertEquals(1, backend.count(attributes, "explicit"))
        assertEquals("push-2", JSONObject(backend.seen.single {
            it.path == attributes && it.phase == "explicit"
        }.body).getString("push_token"))
        backend.assertQuiet(attributes, attempts + 2)
    }

    @Test
    fun `E04 attribute update - with revocation cancellation held, the stale retry cannot use the new grant`() {
        holdCleanup()
        authenticated()
        failIntoRetryWait(attributes, "Set attributes - Failed") { Grovs.pushToken = "push-1" }
        val attempts = backend.count(attributes)

        Grovs.setSDK(false)
        backend.respond(attributes, "{}")
        Grovs.setSDK(true)
        backend.phase = "re-enabled"
        assertTrue("the wait's cancellation is held", gate!!.heldCount > 0)
        driver.advanceTimeBy(intervals)
        pump()

        backend.assertQuiet(attributes, attempts)
        assertEquals("the stale retry woke under the new grant and was refused", 0, backend.count(attributes, "re-enabled"))
    }

    // ==================== Notification families ====================

    @Test
    fun `E04 unread count - disabled during the retry wait, no stale count, an explicit call works`() {
        authenticated()
        val results = CopyOnWriteArrayList<Int?>()
        failIntoRetryWait(unread, "Get unread messages - Failed") { Grovs.numberOfUnreadMessages(null) { results += it } }

        revokeDuringWait(unread)
        assertTrue("no count from a revoked request: $results", results.none { it != null })
        grantAgainAfterRecovery(unread, """{"number_of_unread_notifications":3}""")
        assertEquals("consent alone never replays the request", 0, backend.count(unread, "re-enabled"))

        backend.phase = "explicit"
        Grovs.numberOfUnreadMessages(null) { results += it }
        pumpUntil("the explicit count") { 3 in results }
        assertEquals(1, backend.count(unread, "explicit"))
        assertEquals(listOf(3), results.filterNotNull())
    }

    @Test
    fun `E04 unread count - with revocation cancellation held, the stale retry cannot use the new grant`() {
        holdCleanup()
        authenticated()
        val results = CopyOnWriteArrayList<Int?>()
        failIntoRetryWait(unread, "Get unread messages - Failed") { Grovs.numberOfUnreadMessages(null) { results += it } }
        val attempts = backend.count(unread)

        Grovs.setSDK(false)
        backend.respond(unread, """{"number_of_unread_notifications":3}""")
        Grovs.setSDK(true)
        backend.phase = "re-enabled"
        assertTrue("the wait's cancellation is held", gate!!.heldCount > 0)
        driver.advanceTimeBy(intervals)
        pump()

        backend.assertQuiet(unread, attempts)
        assertEquals(0, backend.count(unread, "re-enabled"))
        assertTrue("no count from the stale request: $results", results.none { it != null })
    }

    @Test
    fun `E04 notifications page from the view model - disabled during the main-looper retry wait`() {
        // The view model calls the service directly, with no inherited operation, on the main looper.
        // Not configured, so point the context at the test backend (never the default server URL).
        context.settings.baseURL = backend.baseUrl
        val service = GrovsService(context = application, apiKey = "test-key", grovsContext = context)
        val viewModel = NotificationsMainViewModel(application).also { it.grovsService = service }
        backend.fail(notifications)
        viewModel.loadMoreNotifications()
        pumpUntil("the page request to fail into its retry wait") {
            backend.count(notifications) >= 1 && logged("Getting all the notifications - Failed")
        }
        assertTrue(viewModel.isLoading.value)
        assertTrue("the retry wait is a registered operation", context.consent.registrationCount() >= 1)
        val attempts = backend.count(notifications)
        val before = SystemClock.uptimeMillis()

        Grovs.setSDK(false)
        backend.phase = "disabled"
        val cleanup = context.consent.pendingWork()
        pumpUntil("the revoked page request to finish") { cleanup.isCompleted }
        assertEquals("revocation ended the wait without main-looper time passing", before, SystemClock.uptimeMillis())
        assertFalse("the loading state is reset", viewModel.isLoading.value)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(intervals))
        backend.assertQuiet(notifications, attempts)

        backend.respond(notifications, """{"notifications":[{"id":7,"title":"n7","updated_at":"2024-01-01T10:00:00.000Z",
            "subtitle":null,"auto_display":false,"access_url":null,"read":false}]}""")
        Grovs.setSDK(true)
        backend.phase = "re-enabled"
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(intervals))
        backend.assertQuiet(notifications, attempts)

        // Explicit new action: the page the revoked request asked for is requested again.
        backend.phase = "explicit"
        viewModel.loadMoreNotifications()
        pumpUntil("the explicit page") { viewModel.notifications.value.size == 1 }
        assertEquals(1, backend.count(notifications, "explicit"))
        assertEquals(1, JSONObject(backend.seen.last { it.path == notifications }.body).getInt("page"))
    }
}
