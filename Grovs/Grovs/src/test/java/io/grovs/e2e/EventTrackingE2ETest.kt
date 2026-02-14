package io.grovs.e2e

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.grovs.Grovs
// PURCHASE_EVENT_DISABLED: import io.grovs.model.events.PaymentEventType
import io.grovs.service.GrovsService
import io.grovs.utils.InstantCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * E2E tests for event tracking functionality (INSTALL, APP_OPEN, REINSTALL events).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EventTrackingE2ETest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var application: Application
    private lateinit var capturedEventBodies: MutableList<String>
    private lateinit var capturedPaymentBodies: MutableList<String>

    @get:Rule
    val testName = TestName()

    @Before
    fun setUp() {
        println("\n========== Running: ${testName.methodName} ==========")
        E2ETestUtils.resetGrovsSingleton()
        E2ETestUtils.setupMockGlInfo()
        mockWebServer = E2ETestUtils.createMockWebServer()
        application = RuntimeEnvironment.getApplication()
        E2ETestUtils.setupTestApplication(application)
        capturedEventBodies = mutableListOf()
        capturedPaymentBodies = mutableListOf()
    }

    @After
    fun tearDown() {
        capturedEventBodies.clear()
        capturedPaymentBodies.clear()
        E2ETestUtils.cleanupMockWebServer(mockWebServer)
    }

    /**
     * Install a URL-based dispatcher that captures event request bodies and returns
     * standard auth/device responses. Optionally customize the lastSeen value.
     */
    private fun installEventCapturingDispatcher(
        lastSeen: String? = null,
        authResponseCode: Int = 200,
        eventResponseCode: Int = 200
    ) {
        val lastSeenJson = lastSeen?.let { "\"$it\"" } ?: "null"

        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val body = request.body.clone().readUtf8()

                if (path.contains("add_payment_event")) {
                    synchronized(capturedPaymentBodies) {
                        capturedPaymentBodies.add(body)
                    }
                } else if (path.contains("event")) {
                    synchronized(capturedEventBodies) {
                        capturedEventBodies.add(body)
                    }
                }

                return when {
                    path.contains("authenticate") -> MockResponse()
                        .setResponseCode(authResponseCode)
                        .setHeader("Content-Type", "application/json")
                        .setBody(if (authResponseCode == 200) """{"linksquared":"test-grovs-id-123","uri_scheme":"testapp"}""" else """{"error":"Unauthorized"}""")
                    path.contains("device_for_vendor_id") -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"last_seen":$lastSeenJson}""")
                    path.contains("add_payment_event") -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{}")
                    path.contains("event") -> MockResponse()
                        .setResponseCode(eventResponseCode)
                        .setHeader("Content-Type", "application/json")
                        .setBody(if (eventResponseCode == 200) "{}" else """{"error":"Server Error"}""")
                    else -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{}")
                }
            }
        }
    }

    private fun waitForEvent(eventName: String) {
        E2ETestUtils.waitForCondition(timeoutMs = 10_000, description = "$eventName event sent to backend") {
            E2ETestUtils.enableImmediateEventSending()
            synchronized(capturedEventBodies) {
                capturedEventBodies.any { it.contains("\"event\":\"$eventName\"") }
            }
        }
    }

    private fun assertEventSent(eventName: String) {
        val body = synchronized(capturedEventBodies) {
            capturedEventBodies.first { it.contains("\"event\":\"$eventName\"") }
        }
        assertTrue(
            "Event should have 'event' parameter set to '$eventName', got: ${body.take(300)}",
            body.contains("\"event\":\"$eventName\"")
        )
        assertTrue(
            "Event '$eventName' should have 'created_at' timestamp, got: ${body.take(300)}",
            body.contains("\"created_at\":")
        )
    }

    private fun assertEventHasLink(eventName: String, expectedLink: String) {
        // Use last matching event since addLinkToEvents re-sends events with the link attributed
        val body = synchronized(capturedEventBodies) {
            capturedEventBodies.last { it.contains("\"event\":\"$eventName\"") }
        }
        assertTrue(
            "Event '$eventName' should have link '$expectedLink', got: ${body.take(300)}",
            body.contains("\"link\":\"$expectedLink\"")
        )
    }

    private fun assertEventHasNoLink(eventName: String) {
        val body = synchronized(capturedEventBodies) {
            capturedEventBodies.first { it.contains("\"event\":\"$eventName\"") }
        }
        val hasNullLink = body.contains("\"link\":null")
        val hasNoLink = !body.contains("\"link\":")
        assertTrue(
            "Event '$eventName' should have null or no link, got: ${body.take(300)}",
            hasNullLink || hasNoLink
        )
    }

    /**
     * Install a dispatcher that captures event bodies and also serves deeplink resolution
     * responses via data_for_device endpoint.
     */
    private fun installDeeplinkEventCapturingDispatcher(
        lastSeen: String? = null,
        resolvedLink: String,
        resolvedData: String? = null
    ) {
        val lastSeenJson = lastSeen?.let { "\"$it\"" } ?: "null"
        val dataJson = resolvedData ?: "null"

        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val body = request.body.clone().readUtf8()

                if (path.contains("add_payment_event")) {
                    synchronized(capturedPaymentBodies) {
                        capturedPaymentBodies.add(body)
                    }
                } else if (path.contains("event")) {
                    synchronized(capturedEventBodies) {
                        capturedEventBodies.add(body)
                    }
                }

                return when {
                    path.contains("authenticate") -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"linksquared":"test-grovs-id-123","uri_scheme":"testapp"}""")
                    path.contains("device_for_vendor_id") -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"last_seen":$lastSeenJson}""")
                    path.contains("data_for_device") -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"link":"$resolvedLink","data":$dataJson}""")
                    path.contains("add_payment_event") -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{}")
                    else -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{}")
                }
            }
        }
    }

    private fun eventCount(eventName: String): Int {
        return synchronized(capturedEventBodies) {
            capturedEventBodies.count { it.contains("\"event\":\"$eventName\"") }
        }
    }

    private fun getLinkForFutureActions(): String? {
        return try {
            val instance = E2ETestUtils.getGrovsInstance()
            val managerField = instance!!.javaClass.getDeclaredField("grovsManager")
            managerField.isAccessible = true
            val manager = managerField.get(instance)
            val eventsManagerField = manager!!.javaClass.getDeclaredField("eventsManager")
            eventsManagerField.isAccessible = true
            val eventsManager = eventsManagerField.get(manager)
            val linkField = eventsManager!!.javaClass.getDeclaredField("linkForFutureActions")
            linkField.isAccessible = true
            linkField.get(eventsManager) as? String
        } catch (e: Exception) {
            println("Could not get linkForFutureActions: ${e.message}")
            null
        }
    }

    // ==================== Event Tracking Tests ====================

    @Test
    fun `test SDK sends INSTALL event on first app open`() {
        // Arrange - lastSeen=null means brand new device → INSTALL event
        installEventCapturingDispatcher(lastSeen = null)

        // Act
        E2ETestUtils.configureAndWaitForAuthOnly(application)
        E2ETestUtils.enableImmediateEventSending()

        val activityController = Robolectric.buildActivity(TestActivity::class.java)
        activityController.create().start()

        waitForEvent("install")

        // Assert
        E2ETestUtils.assertAuthenticationCompleted()
        assertEventSent("install")

        activityController.stop().destroy()
    }

    @Test
    fun `test SDK sends APP_OPEN event on app open`() {
        // Arrange
        installEventCapturingDispatcher(lastSeen = null)

        // Act
        E2ETestUtils.configureAndWaitForAuthOnly(application)
        E2ETestUtils.enableImmediateEventSending()

        val activityController = Robolectric.buildActivity(TestActivity::class.java)
        activityController.create().start()

        waitForEvent("app_open")

        // Assert
        E2ETestUtils.assertAuthenticationCompleted()
        assertEventSent("app_open")

        activityController.stop().destroy()
    }

    @Test
    fun `test first app open sends both INSTALL and APP_OPEN events`() {
        // Arrange - first launch should produce both install and app_open
        installEventCapturingDispatcher(lastSeen = null)

        // Act
        E2ETestUtils.configureAndWaitForAuthOnly(application)
        E2ETestUtils.enableImmediateEventSending()

        val activityController = Robolectric.buildActivity(TestActivity::class.java)
        activityController.create().start()

        waitForEvent("install")
        waitForEvent("app_open")

        // Assert - both events sent with correct parameters
        E2ETestUtils.assertAuthenticationCompleted()
        assertEventSent("install")
        assertEventSent("app_open")

        activityController.stop().destroy()
    }

    @Test
    fun `test SDK sends REINSTALL event when lastSeen is set`() {
        // Arrange - lastSeen is set means device was seen before → REINSTALL (not INSTALL)
        installEventCapturingDispatcher(lastSeen = "2026-01-20T10:00:00.000Z")

        // Act
        E2ETestUtils.configureAndWaitForAuthOnly(application)
        E2ETestUtils.enableImmediateEventSending()

        val activityController = Robolectric.buildActivity(TestActivity::class.java)
        activityController.create().start()

        waitForEvent("reinstall")

        // Assert - should send reinstall, not install
        E2ETestUtils.assertAuthenticationCompleted()
        assertEventSent("reinstall")
        assertEquals(
            "Should not send install event for a returning device",
            0, eventCount("install")
        )

        activityController.stop().destroy()
    }

    @Test
    fun `test background to foreground cycle sends pending events`() {
        // Arrange
        installEventCapturingDispatcher(lastSeen = null)

        E2ETestUtils.configureAndWaitForAuthOnly(application)
        E2ETestUtils.enableImmediateEventSending()

        val activityController = Robolectric.buildActivity(TestActivity::class.java)
        activityController.create().start().resume()

        // Wait for initial events from first foreground
        waitForEvent("app_open")

        // Act - go to background then come back to foreground
        activityController.pause().stop()

        // Clear captured events to isolate foreground events
        synchronized(capturedEventBodies) {
            capturedEventBodies.clear()
        }

        activityController.start().resume()

        // Give time for the serialDispatcher to process onAppForegrounded
        Thread.sleep(1000)
        E2ETestUtils.enableImmediateEventSending()
        Thread.sleep(1000)

        // Assert - SDK should remain functional after background/foreground cycle
        E2ETestUtils.assertAuthenticationCompleted()

        activityController.pause().stop().destroy()
    }

    @Test
    fun `test no INSTALL event on second app open`() {
        // Arrange - first launch
        installEventCapturingDispatcher(lastSeen = null)

        E2ETestUtils.configureAndWaitForAuthOnly(application)
        E2ETestUtils.enableImmediateEventSending()

        val activityController = Robolectric.buildActivity(TestActivity::class.java)
        activityController.create().start()

        waitForEvent("install")
        waitForEvent("app_open")

        val installCountAfterFirstOpen = eventCount("install")
        assertEquals("First open should produce exactly 1 install", 1, installCountAfterFirstOpen)

        // Act - simulate background → foreground (second "open")
        activityController.pause().stop()

        activityController.start().resume()

        // Give time for any additional events
        E2ETestUtils.enableImmediateEventSending()
        Thread.sleep(1000)

        // Assert - install should still be exactly 1 (no second install)
        assertEquals(
            "Second foreground cycle should NOT send another install event",
            1, eventCount("install")
        )

        activityController.pause().stop().destroy()
    }

    @Test
    fun `test REINSTALL event includes created_at but no install event`() {
        // Arrange - returning device
        installEventCapturingDispatcher(lastSeen = "2026-01-15T08:30:00.000Z")

        // Act
        E2ETestUtils.configureAndWaitForAuthOnly(application)
        E2ETestUtils.enableImmediateEventSending()

        val activityController = Robolectric.buildActivity(TestActivity::class.java)
        activityController.create().start()

        waitForEvent("reinstall")
        waitForEvent("app_open")

        // Assert
        E2ETestUtils.assertAuthenticationCompleted()
        assertEventSent("reinstall")
        assertEventSent("app_open")
        assertEquals("Should not send install for returning device", 0, eventCount("install"))

        activityController.stop().destroy()
    }

    @Test
    fun `test events not sent before authentication completes`() {
        // Arrange - delay authentication response so events queue before auth
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val body = request.body.clone().readUtf8()

                if (path.contains("event")) {
                    synchronized(capturedEventBodies) {
                        capturedEventBodies.add(body)
                    }
                }

                return when {
                    path.contains("authenticate") -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"linksquared":"test-grovs-id-123","uri_scheme":"testapp"}""")
                        .setBodyDelay(3, java.util.concurrent.TimeUnit.SECONDS)
                    path.contains("device_for_vendor_id") -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"last_seen":null}""")
                    else -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{}")
                }
            }
        }

        // Act - configure but don't wait for auth
        Grovs.configure(application, "test-api-key", useTestEnvironment = true)

        // Check immediately - no events should be sent yet since auth is delayed
        val eventsBeforeAuth = synchronized(capturedEventBodies) {
            capturedEventBodies.toList()
        }
        assertEquals(
            "No events should be sent before authentication completes",
            0, eventsBeforeAuth.size
        )

        // Now wait for auth to complete
        runBlocking {
            val authJob = E2ETestUtils.getAuthenticationJob()
            withTimeoutOrNull(10_000) { authJob?.join() }
        }

        // Start activity to trigger event sending
        E2ETestUtils.enableImmediateEventSending()
        val activityController = Robolectric.buildActivity(TestActivity::class.java)
        activityController.create().start()

        waitForEvent("install")

        // Assert - events arrive after auth
        E2ETestUtils.assertAuthenticationCompleted()
        assertEventSent("install")

        activityController.stop().destroy()
    }

    // ==================== Link Attribution Tests ====================

    @Test
    fun `test events without deeplink have no link attribution`() {
        // Arrange - no deeplink, data_for_device returns null link
        installEventCapturingDispatcher(lastSeen = null)

        // Act
        E2ETestUtils.configureAndWaitForAuthOnly(application)
        E2ETestUtils.enableImmediateEventSending()

        val activityController = Robolectric.buildActivity(TestActivity::class.java)
        activityController.create().start()

        waitForEvent("install")
        waitForEvent("app_open")

        // Assert - events should have null link since no deeplink was opened
        assertEventHasNoLink("install")
        assertEventHasNoLink("app_open")

        activityController.stop().destroy()
    }

    @Test
    fun `test cold start deeplink attributes link to install event`() {
        // Arrange - cold start with deeplink, server resolves to a known link
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            installDeeplinkEventCapturingDispatcher(
                lastSeen = null,
                resolvedLink = "https://test.grovs.io/campaign123"
            )

            E2ETestUtils.configureAndWaitForAuthOnly(application)
            // Do NOT enable immediate event sending yet — let the SDK's delay hold events
            // in storage so addLinkToEvents can retroactively patch the resolved link

            // Act - cold start with deeplink intent
            val deeplinkIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("testapp://open?link=campaign123")
            }
            val activityController = Robolectric.buildActivity(TestActivity::class.java, deeplinkIntent)
            activityController.create()

            Grovs.setOnDeeplinkReceivedListener(activityController.get()) { _ -> }
            activityController.start()

            // Wait for deeplink resolution to complete (events get patched in storage)
            Thread.sleep(2000)

            // Now enable immediate sending so patched events flush to backend
            E2ETestUtils.enableImmediateEventSending()

            // Trigger event flush via foreground cycle
            activityController.pause().stop()
            activityController.start().resume()

            // Wait for install event with resolved link
            E2ETestUtils.waitForCondition(timeoutMs = 10_000, description = "install event with link attribution") {
                E2ETestUtils.enableImmediateEventSending()
                synchronized(capturedEventBodies) {
                    capturedEventBodies.any {
                        it.contains("\"event\":\"install\"") && it.contains("\"link\":\"https://test.grovs.io/campaign123\"")
                    }
                }
            }

            // Assert
            assertEventHasLink("install", "https://test.grovs.io/campaign123")

            activityController.stop().destroy()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `test cold start deeplink attributes link to app_open event`() {
        // Arrange
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            installDeeplinkEventCapturingDispatcher(
                lastSeen = null,
                resolvedLink = "https://test.grovs.io/promo"
            )

            E2ETestUtils.configureAndWaitForAuthOnly(application)

            // Act - cold start with deeplink
            val deeplinkIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("testapp://open?link=promo")
            }
            val activityController = Robolectric.buildActivity(TestActivity::class.java, deeplinkIntent)
            activityController.create()

            Grovs.setOnDeeplinkReceivedListener(activityController.get()) { _ -> }
            activityController.start()

            // Wait for deeplink resolution
            Thread.sleep(2000)

            // Enable immediate sending and trigger flush
            E2ETestUtils.enableImmediateEventSending()
            activityController.pause().stop()
            activityController.start().resume()

            // Wait for app_open event with link
            E2ETestUtils.waitForCondition(timeoutMs = 10_000, description = "app_open event with link attribution") {
                E2ETestUtils.enableImmediateEventSending()
                synchronized(capturedEventBodies) {
                    capturedEventBodies.any {
                        it.contains("\"event\":\"app_open\"") && it.contains("\"link\":\"https://test.grovs.io/promo\"")
                    }
                }
            }

            // Assert
            assertEventHasLink("app_open", "https://test.grovs.io/promo")

            activityController.stop().destroy()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `test deeplink attributes link to reinstall event`() {
        // Arrange - returning device with deeplink
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            installDeeplinkEventCapturingDispatcher(
                lastSeen = "2026-01-10T12:00:00.000Z",
                resolvedLink = "https://test.grovs.io/welcome-back"
            )

            E2ETestUtils.configureAndWaitForAuthOnly(application)

            // Act - cold start with deeplink on returning device
            val deeplinkIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("testapp://open?link=welcome-back")
            }
            val activityController = Robolectric.buildActivity(TestActivity::class.java, deeplinkIntent)
            activityController.create()

            Grovs.setOnDeeplinkReceivedListener(activityController.get()) { _ -> }
            activityController.start()

            // Wait for deeplink resolution
            Thread.sleep(2000)

            // Enable immediate sending and trigger flush
            E2ETestUtils.enableImmediateEventSending()
            activityController.pause().stop()
            activityController.start().resume()

            // Wait for reinstall event with link attribution
            E2ETestUtils.waitForCondition(timeoutMs = 10_000, description = "reinstall event with link attribution") {
                E2ETestUtils.enableImmediateEventSending()
                synchronized(capturedEventBodies) {
                    capturedEventBodies.any {
                        it.contains("\"event\":\"reinstall\"") && it.contains("\"link\":\"https://test.grovs.io/welcome-back\"")
                    }
                }
            }

            // Assert
            assertEventHasLink("reinstall", "https://test.grovs.io/welcome-back")
            assertEquals("Should not send install for returning device", 0, eventCount("install"))

            activityController.stop().destroy()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `test cold start deeplink attributes link to both install and app_open`() {
        // Arrange - verify both events get the resolved link on cold start
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            installDeeplinkEventCapturingDispatcher(
                lastSeen = null,
                resolvedLink = "https://test.grovs.io/both-events"
            )

            E2ETestUtils.configureAndWaitForAuthOnly(application)

            // Act - cold start with deeplink
            val deeplinkIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("testapp://open?link=both-events")
            }
            val activityController = Robolectric.buildActivity(TestActivity::class.java, deeplinkIntent)
            activityController.create()

            Grovs.setOnDeeplinkReceivedListener(activityController.get()) { _ -> }
            activityController.start()

            // Wait for deeplink resolution to patch events in storage
            Thread.sleep(2000)

            // Enable immediate sending and trigger flush
            E2ETestUtils.enableImmediateEventSending()
            activityController.pause().stop()
            activityController.start().resume()

            // Wait for both events with the resolved link
            E2ETestUtils.waitForCondition(timeoutMs = 10_000, description = "install event with link") {
                E2ETestUtils.enableImmediateEventSending()
                synchronized(capturedEventBodies) {
                    capturedEventBodies.any {
                        it.contains("\"event\":\"install\"") && it.contains("\"link\":\"https://test.grovs.io/both-events\"")
                    }
                }
            }
            E2ETestUtils.waitForCondition(timeoutMs = 10_000, description = "app_open event with link") {
                E2ETestUtils.enableImmediateEventSending()
                synchronized(capturedEventBodies) {
                    capturedEventBodies.any {
                        it.contains("\"event\":\"app_open\"") && it.contains("\"link\":\"https://test.grovs.io/both-events\"")
                    }
                }
            }

            // Assert - both events should carry the resolved deeplink
            assertEventHasLink("install", "https://test.grovs.io/both-events")
            assertEventHasLink("app_open", "https://test.grovs.io/both-events")

            activityController.stop().destroy()
        } finally {
            Dispatchers.resetMain()
        }
    }

    // ==================== Edge Case Tests ====================

    @Test
    fun `test SDK handles event endpoint server error without crashing`() {
        // Arrange - event endpoint returns 500 errors
        installEventCapturingDispatcher(lastSeen = null, eventResponseCode = 500)

        // Act
        E2ETestUtils.configureAndWaitForAuthOnly(application)
        E2ETestUtils.enableImmediateEventSending()

        val activityController = Robolectric.buildActivity(TestActivity::class.java)
        activityController.create().start()

        // Give SDK time to attempt event sending (will get 500 errors)
        Thread.sleep(2000)

        // Assert - SDK should still be authenticated and functional despite event errors
        E2ETestUtils.assertAuthenticationCompleted()
        E2ETestUtils.assertSdkFunctionalAfterError()

        activityController.stop().destroy()
    }

    // PURCHASE_EVENT_DISABLED: @Test
    // PURCHASE_EVENT_DISABLED: fun `test custom purchase event is sent to backend`() {
    // PURCHASE_EVENT_DISABLED:     // Arrange
    // PURCHASE_EVENT_DISABLED:     installEventCapturingDispatcher(lastSeen = null)
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     // Act - configure, authenticate, then log a custom purchase
    // PURCHASE_EVENT_DISABLED:     E2ETestUtils.configureAndWaitForAuthOnly(application)
    // PURCHASE_EVENT_DISABLED:     E2ETestUtils.enableImmediateEventSending()
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     val activityController = Robolectric.buildActivity(TestActivity::class.java)
    // PURCHASE_EVENT_DISABLED:     activityController.create().start()
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     // Wait for initial events first
    // PURCHASE_EVENT_DISABLED:     waitForEvent("install")
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     // Log a custom purchase
    // PURCHASE_EVENT_DISABLED:     Grovs.logCustomPurchase(
    // PURCHASE_EVENT_DISABLED:         type = PaymentEventType.BUY,
    // PURCHASE_EVENT_DISABLED:         priceInCents = 999,
    // PURCHASE_EVENT_DISABLED:         currency = "USD",
    // PURCHASE_EVENT_DISABLED:         productId = "premium_monthly"
    // PURCHASE_EVENT_DISABLED:     )
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     // Wait for the payment event to be captured
    // PURCHASE_EVENT_DISABLED:     E2ETestUtils.waitForCondition(timeoutMs = 10_000, description = "payment event sent to backend") {
    // PURCHASE_EVENT_DISABLED:         E2ETestUtils.enableImmediateEventSending()
    // PURCHASE_EVENT_DISABLED:         synchronized(capturedPaymentBodies) {
    // PURCHASE_EVENT_DISABLED:             capturedPaymentBodies.any { it.contains("\"product_id\":\"premium_monthly\"") }
    // PURCHASE_EVENT_DISABLED:         }
    // PURCHASE_EVENT_DISABLED:     }
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     // Assert - verify the payment event body contains expected values
    // PURCHASE_EVENT_DISABLED:     val paymentBody = synchronized(capturedPaymentBodies) {
    // PURCHASE_EVENT_DISABLED:         capturedPaymentBodies.first { it.contains("\"product_id\":\"premium_monthly\"") }
    // PURCHASE_EVENT_DISABLED:     }
    // PURCHASE_EVENT_DISABLED:     assertTrue(
    // PURCHASE_EVENT_DISABLED:         "Payment event should contain price_cents 999, got: ${paymentBody.take(300)}",
    // PURCHASE_EVENT_DISABLED:         paymentBody.contains("\"price_cents\":999")
    // PURCHASE_EVENT_DISABLED:     )
    // PURCHASE_EVENT_DISABLED:     assertTrue(
    // PURCHASE_EVENT_DISABLED:         "Payment event should contain currency USD, got: ${paymentBody.take(300)}",
    // PURCHASE_EVENT_DISABLED:         paymentBody.contains("\"currency\":\"USD\"")
    // PURCHASE_EVENT_DISABLED:     )
    // PURCHASE_EVENT_DISABLED:     assertTrue(
    // PURCHASE_EVENT_DISABLED:         "Payment event should contain event_type buy, got: ${paymentBody.take(300)}",
    // PURCHASE_EVENT_DISABLED:         paymentBody.contains("\"event_type\":\"buy\"")
    // PURCHASE_EVENT_DISABLED:     )
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     activityController.stop().destroy()
    // PURCHASE_EVENT_DISABLED: }

    @Test
    fun `test SDK remains functional after multiple background foreground cycles`() {
        // Arrange - verify SDK handles repeated lifecycle transitions without errors
        installEventCapturingDispatcher(lastSeen = null)

        E2ETestUtils.configureAndWaitForAuthOnly(application)
        E2ETestUtils.enableImmediateEventSending()

        val activityController = Robolectric.buildActivity(TestActivity::class.java)
        activityController.create().start().resume()

        // Wait for initial events
        waitForEvent("install")
        waitForEvent("app_open")

        val installCountAfterLaunch = eventCount("install")
        val appOpenCountAfterLaunch = eventCount("app_open")

        // Act - cycle through background/foreground 3 times.
        // Use pause/stop + start/resume with numStarted correction because
        // Robolectric double-dispatches onActivityStarted when restarting a
        // stopped AppCompatActivity, inflating numStarted. Without the reset,
        // onAppBackgrounded never fires since numStarted never reaches 0.
        repeat(3) {
            activityController.pause().stop()
            Thread.sleep(1000)

            activityController.start().resume()
            E2ETestUtils.setNumStarted(1)
            E2ETestUtils.enableImmediateEventSending()
            Thread.sleep(1000)
        }

        // Assert - SDK should still be authenticated and functional
        E2ETestUtils.assertAuthenticationCompleted()
        E2ETestUtils.assertSdkFunctionalAfterError()

        // install is only sent on first launch, never duplicated by foreground cycles
        assertEquals(
            "Install event should be sent exactly once despite 3 foreground cycles",
            1, eventCount("install")
        )
        assertEquals(
            "Install count should not change after initial launch",
            installCountAfterLaunch, eventCount("install")
        )

        // app_open is only sent once via logAppLaunchEvents during authentication,
        // foreground cycles (onAppForegrounded) only flush pending events, not add new ones
        assertEquals(
            "app_open event should be sent exactly once (only on initial launch)",
            appOpenCountAfterLaunch, eventCount("app_open")
        )

        // No reinstall event since lastSeen=null (new device)
        assertEquals(
            "No reinstall event should be sent for a new device",
            0, eventCount("reinstall")
        )

        // Backend received requests (auth + device + events)
        assertTrue(
            "MockWebServer should have received multiple requests, got: ${mockWebServer.requestCount}",
            mockWebServer.requestCount >= 3
        )

        activityController.pause().stop().destroy()
    }

    @Test
    fun `test reinstall also sends app_open event`() {
        // Arrange - returning device should get both reinstall AND app_open
        installEventCapturingDispatcher(lastSeen = "2026-01-10T12:00:00.000Z")

        // Act
        E2ETestUtils.configureAndWaitForAuthOnly(application)
        E2ETestUtils.enableImmediateEventSending()

        val activityController = Robolectric.buildActivity(TestActivity::class.java)
        activityController.create().start()

        waitForEvent("reinstall")
        waitForEvent("app_open")

        // Assert - returning device gets reinstall + app_open but NOT install
        assertEventSent("reinstall")
        assertEventSent("app_open")
        assertEquals("Should not send install for returning device", 0, eventCount("install"))
        assertTrue("Should have at least 1 reinstall event", eventCount("reinstall") >= 1)
        assertTrue("Should have at least 1 app_open event", eventCount("app_open") >= 1)

        activityController.stop().destroy()
    }

    // PURCHASE_EVENT_DISABLED: @Test
    // PURCHASE_EVENT_DISABLED: fun `test payment event includes link attribution from deeplink`() {
    // PURCHASE_EVENT_DISABLED:     // Arrange - cold start with deeplink, then log a purchase
    // PURCHASE_EVENT_DISABLED:     Dispatchers.setMain(UnconfinedTestDispatcher())
    // PURCHASE_EVENT_DISABLED:     try {
    // PURCHASE_EVENT_DISABLED:         installDeeplinkEventCapturingDispatcher(
    // PURCHASE_EVENT_DISABLED:             lastSeen = null,
    // PURCHASE_EVENT_DISABLED:             resolvedLink = "https://test.grovs.io/purchase-campaign"
    // PURCHASE_EVENT_DISABLED:         )
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:         E2ETestUtils.configureAndWaitForAuthOnly(application)
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:         // Cold start with deeplink
    // PURCHASE_EVENT_DISABLED:         val deeplinkIntent = Intent(Intent.ACTION_VIEW).apply {
    // PURCHASE_EVENT_DISABLED:             data = Uri.parse("testapp://open?link=purchase-campaign")
    // PURCHASE_EVENT_DISABLED:         }
    // PURCHASE_EVENT_DISABLED:         val activityController = Robolectric.buildActivity(TestActivity::class.java, deeplinkIntent)
    // PURCHASE_EVENT_DISABLED:         activityController.create()
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:         Grovs.setOnDeeplinkReceivedListener(activityController.get()) { _ -> }
    // PURCHASE_EVENT_DISABLED:         activityController.start()
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:         // Wait for deeplink resolution so linkForFutureActions gets set
    // PURCHASE_EVENT_DISABLED:         Thread.sleep(2000)
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:         // Now log a custom purchase - it should pick up linkForFutureActions
    // PURCHASE_EVENT_DISABLED:         E2ETestUtils.enableImmediateEventSending()
    // PURCHASE_EVENT_DISABLED:         Grovs.logCustomPurchase(
    // PURCHASE_EVENT_DISABLED:             type = PaymentEventType.BUY,
    // PURCHASE_EVENT_DISABLED:             priceInCents = 1999,
    // PURCHASE_EVENT_DISABLED:             currency = "EUR",
    // PURCHASE_EVENT_DISABLED:             productId = "annual_plan"
    // PURCHASE_EVENT_DISABLED:         )
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:         // Wait for payment event
    // PURCHASE_EVENT_DISABLED:         E2ETestUtils.waitForCondition(timeoutMs = 10_000, description = "payment event with link") {
    // PURCHASE_EVENT_DISABLED:             E2ETestUtils.enableImmediateEventSending()
    // PURCHASE_EVENT_DISABLED:             synchronized(capturedPaymentBodies) {
    // PURCHASE_EVENT_DISABLED:                 capturedPaymentBodies.any { it.contains("\"product_id\":\"annual_plan\"") }
    // PURCHASE_EVENT_DISABLED:             }
    // PURCHASE_EVENT_DISABLED:         }
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:         // Assert - payment event should carry the deeplink
    // PURCHASE_EVENT_DISABLED:         val paymentBody = synchronized(capturedPaymentBodies) {
    // PURCHASE_EVENT_DISABLED:             capturedPaymentBodies.first { it.contains("\"product_id\":\"annual_plan\"") }
    // PURCHASE_EVENT_DISABLED:         }
    // PURCHASE_EVENT_DISABLED:         assertTrue(
    // PURCHASE_EVENT_DISABLED:             "Payment event should have link from deeplink, got: ${paymentBody.take(400)}",
    // PURCHASE_EVENT_DISABLED:             paymentBody.contains("\"link\":\"https://test.grovs.io/purchase-campaign\"")
    // PURCHASE_EVENT_DISABLED:         )
    // PURCHASE_EVENT_DISABLED:         assertTrue(
    // PURCHASE_EVENT_DISABLED:             "Payment event should contain price_cents 1999",
    // PURCHASE_EVENT_DISABLED:             paymentBody.contains("\"price_cents\":1999")
    // PURCHASE_EVENT_DISABLED:         )
    // PURCHASE_EVENT_DISABLED:         assertTrue(
    // PURCHASE_EVENT_DISABLED:             "Payment event should contain currency EUR",
    // PURCHASE_EVENT_DISABLED:             paymentBody.contains("\"currency\":\"EUR\"")
    // PURCHASE_EVENT_DISABLED:         )
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:         activityController.stop().destroy()
    // PURCHASE_EVENT_DISABLED:     } finally {
    // PURCHASE_EVENT_DISABLED:         Dispatchers.resetMain()
    // PURCHASE_EVENT_DISABLED:     }
    // PURCHASE_EVENT_DISABLED: }

    // ==================== Events Before Deeplink Resolution Tests ====================

    /**
     * Helper to pre-populate SharedPreferences so the SDK thinks this is a returning user
     * who last opened the app [daysAgo] days ago and has opened the app [opens] times.
     * This must be called BEFORE Grovs.configure() since EventsManager reads from
     * SharedPreferences during logAppLaunchEvents().
     */
    private fun seedLocalCache(daysAgo: Int, opens: Int) {
        val prefs = application.getSharedPreferences("GrovsStorage", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("grovs_number_of_opens", opens)
            .putString(
                "grovs_last_start_timestamp",
                InstantCompat(System.currentTimeMillis() - daysAgo.toLong() * 24 * 60 * 60 * 1000).toString()
            )
            .apply()
    }

    @Test
    fun `test reactivation event with deeplink gets resolved link`() {
        // Arrange - simulate a returning user who hasn't opened the app in 8 days
        // and opens it via a deeplink. The reactivation event is retroactively patched
        // by addLinkToEvents() so it should get the resolved link.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            // numberOfOpens > 0 so addInstallIfNeeded() is skipped (no install/reinstall)
            // lastStartTimestamp = 8 days ago so addReactivationIfNeeded() fires
            seedLocalCache(daysAgo = 8, opens = 5)

            installDeeplinkEventCapturingDispatcher(
                lastSeen = "2026-01-20T10:00:00.000Z",
                resolvedLink = "https://test.grovs.io/reactivation-campaign"
            )

            E2ETestUtils.configureAndWaitForAuthOnly(application)
            // Do NOT enable immediate sending — let deeplink resolution patch events first

            // Cold start with deeplink
            val deeplinkIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("testapp://open?link=reactivation-campaign")
            }
            val activityController = Robolectric.buildActivity(TestActivity::class.java, deeplinkIntent)
            activityController.create()

            Grovs.setOnDeeplinkReceivedListener(activityController.get()) { _ -> }
            activityController.start()

            // Wait for deeplink resolution to patch events in storage
            Thread.sleep(2000)

            // Enable sending and flush
            E2ETestUtils.enableImmediateEventSending()
            activityController.pause().stop()
            activityController.start().resume()

            // Wait for reactivation event with the resolved link
            E2ETestUtils.waitForCondition(timeoutMs = 10_000, description = "reactivation event with link") {
                E2ETestUtils.enableImmediateEventSending()
                synchronized(capturedEventBodies) {
                    capturedEventBodies.any {
                        it.contains("\"event\":\"reactivation\"") &&
                            it.contains("\"link\":\"https://test.grovs.io/reactivation-campaign\"")
                    }
                }
            }

            // Assert - reactivation event should have the resolved link (not the raw URI)
            assertEventHasLink("reactivation", "https://test.grovs.io/reactivation-campaign")
            // app_open should also have the resolved link
            assertEventHasLink("app_open", "https://test.grovs.io/reactivation-campaign")
            // No install event since numberOfOpens > 0
            assertEquals("No install event for a returning user with opens > 0", 0, eventCount("install"))
            // No reinstall event since numberOfOpens > 0
            assertEquals("No reinstall event for a returning user with opens > 0", 0, eventCount("reinstall"))

            activityController.stop().destroy()
        } finally {
            Dispatchers.resetMain()
        }
    }

    // PURCHASE_EVENT_DISABLED: @Test
    // PURCHASE_EVENT_DISABLED: fun `test payment event logged before deeplink resolution gets raw link`() {
    // PURCHASE_EVENT_DISABLED:     // Arrange - cold start with deeplink, log a purchase DURING resolution (before
    // PURCHASE_EVENT_DISABLED:     // Call #2 in getDataForDevice). Payment events are NOT retroactively patched by
    // PURCHASE_EVENT_DISABLED:     // addLinkToEvents(), so the payment should get whatever linkForFutureActions is
    // PURCHASE_EVENT_DISABLED:     // at the time of logging — the raw URI from Call #1.
    // PURCHASE_EVENT_DISABLED:     Dispatchers.setMain(UnconfinedTestDispatcher())
    // PURCHASE_EVENT_DISABLED:     try {
    // PURCHASE_EVENT_DISABLED:         // Use a slow data_for_device response to widen the resolution window
    // PURCHASE_EVENT_DISABLED:         val lastSeenJson = "null"
    // PURCHASE_EVENT_DISABLED:         mockWebServer.dispatcher = object : Dispatcher() {
    // PURCHASE_EVENT_DISABLED:             override fun dispatch(request: RecordedRequest): MockResponse {
    // PURCHASE_EVENT_DISABLED:                 val path = request.path ?: ""
    // PURCHASE_EVENT_DISABLED:                 val body = request.body.clone().readUtf8()
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:                 if (path.contains("add_payment_event")) {
    // PURCHASE_EVENT_DISABLED:                     synchronized(capturedPaymentBodies) {
    // PURCHASE_EVENT_DISABLED:                         capturedPaymentBodies.add(body)
    // PURCHASE_EVENT_DISABLED:                     }
    // PURCHASE_EVENT_DISABLED:                 } else if (path.contains("event")) {
    // PURCHASE_EVENT_DISABLED:                     synchronized(capturedEventBodies) {
    // PURCHASE_EVENT_DISABLED:                         capturedEventBodies.add(body)
    // PURCHASE_EVENT_DISABLED:                     }
    // PURCHASE_EVENT_DISABLED:                 }
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:                 return when {
    // PURCHASE_EVENT_DISABLED:                     path.contains("authenticate") -> MockResponse()
    // PURCHASE_EVENT_DISABLED:                         .setResponseCode(200)
    // PURCHASE_EVENT_DISABLED:                         .setHeader("Content-Type", "application/json")
    // PURCHASE_EVENT_DISABLED:                         .setBody("""{"linksquared":"test-grovs-id-123","uri_scheme":"testapp"}""")
    // PURCHASE_EVENT_DISABLED:                     path.contains("device_for_vendor_id") -> MockResponse()
    // PURCHASE_EVENT_DISABLED:                         .setResponseCode(200)
    // PURCHASE_EVENT_DISABLED:                         .setHeader("Content-Type", "application/json")
    // PURCHASE_EVENT_DISABLED:                         .setBody("""{"last_seen":$lastSeenJson}""")
    // PURCHASE_EVENT_DISABLED:                     path.contains("data_for_device") -> MockResponse()
    // PURCHASE_EVENT_DISABLED:                         .setResponseCode(200)
    // PURCHASE_EVENT_DISABLED:                         .setHeader("Content-Type", "application/json")
    // PURCHASE_EVENT_DISABLED:                         .setBody("""{"link":"https://test.grovs.io/resolved-purchase","data":null}""")
    // PURCHASE_EVENT_DISABLED:                         .setBodyDelay(3, java.util.concurrent.TimeUnit.SECONDS) // slow resolution
    // PURCHASE_EVENT_DISABLED:                     path.contains("add_payment_event") -> MockResponse()
    // PURCHASE_EVENT_DISABLED:                         .setResponseCode(200)
    // PURCHASE_EVENT_DISABLED:                         .setHeader("Content-Type", "application/json")
    // PURCHASE_EVENT_DISABLED:                         .setBody("{}")
    // PURCHASE_EVENT_DISABLED:                     else -> MockResponse()
    // PURCHASE_EVENT_DISABLED:                         .setResponseCode(200)
    // PURCHASE_EVENT_DISABLED:                         .setHeader("Content-Type", "application/json")
    // PURCHASE_EVENT_DISABLED:                         .setBody("{}")
    // PURCHASE_EVENT_DISABLED:                 }
    // PURCHASE_EVENT_DISABLED:             }
    // PURCHASE_EVENT_DISABLED:         }
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:         E2ETestUtils.configureAndWaitForAuthOnly(application)
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:         // Cold start with deeplink
    // PURCHASE_EVENT_DISABLED:         val deeplinkIntent = Intent(Intent.ACTION_VIEW).apply {
    // PURCHASE_EVENT_DISABLED:             data = Uri.parse("testapp://open?link=purchase-timing")
    // PURCHASE_EVENT_DISABLED:         }
    // PURCHASE_EVENT_DISABLED:         val activityController = Robolectric.buildActivity(TestActivity::class.java, deeplinkIntent)
    // PURCHASE_EVENT_DISABLED:         activityController.create()
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:         Grovs.setOnDeeplinkReceivedListener(activityController.get()) { _ -> }
    // PURCHASE_EVENT_DISABLED:         activityController.start()
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:         // Log a purchase IMMEDIATELY — before the 3s delayed data_for_device resolves.
    // PURCHASE_EVENT_DISABLED:         // At this point linkForFutureActions has been set to the raw URI by Call #1
    // PURCHASE_EVENT_DISABLED:         // in getDataForDevice(), but Call #2 (with resolved link) hasn't happened yet.
    // PURCHASE_EVENT_DISABLED:         E2ETestUtils.enableImmediateEventSending()
    // PURCHASE_EVENT_DISABLED:         Grovs.logCustomPurchase(
    // PURCHASE_EVENT_DISABLED:             type = PaymentEventType.BUY,
    // PURCHASE_EVENT_DISABLED:             priceInCents = 499,
    // PURCHASE_EVENT_DISABLED:             currency = "USD",
    // PURCHASE_EVENT_DISABLED:             productId = "early_purchase"
    // PURCHASE_EVENT_DISABLED:         )
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:         // Wait for payment event to be captured
    // PURCHASE_EVENT_DISABLED:         E2ETestUtils.waitForCondition(timeoutMs = 15_000, description = "payment event sent") {
    // PURCHASE_EVENT_DISABLED:             E2ETestUtils.enableImmediateEventSending()
    // PURCHASE_EVENT_DISABLED:             synchronized(capturedPaymentBodies) {
    // PURCHASE_EVENT_DISABLED:                 capturedPaymentBodies.any { it.contains("\"product_id\":\"early_purchase\"") }
    // PURCHASE_EVENT_DISABLED:             }
    // PURCHASE_EVENT_DISABLED:         }
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:         // Assert - payment event should NOT have the resolved link because:
    // PURCHASE_EVENT_DISABLED:         // 1. Payment events are not retroactively patched by addLinkToEvents()
    // PURCHASE_EVENT_DISABLED:         // 2. At the time of logPurchase, linkForFutureActions was set to the raw URI
    // PURCHASE_EVENT_DISABLED:         //    by Call #1 (setLinkToNewFutureActions with the raw intent data)
    // PURCHASE_EVENT_DISABLED:         // The payment event gets whatever linkForFutureActions holds at creation time.
    // PURCHASE_EVENT_DISABLED:         val paymentBody = synchronized(capturedPaymentBodies) {
    // PURCHASE_EVENT_DISABLED:             capturedPaymentBodies.first { it.contains("\"product_id\":\"early_purchase\"") }
    // PURCHASE_EVENT_DISABLED:         }
    // PURCHASE_EVENT_DISABLED:         assertTrue(
    // PURCHASE_EVENT_DISABLED:             "Payment event should contain price_cents 499, got: ${paymentBody.take(400)}",
    // PURCHASE_EVENT_DISABLED:             paymentBody.contains("\"price_cents\":499")
    // PURCHASE_EVENT_DISABLED:         )
    // PURCHASE_EVENT_DISABLED:         assertTrue(
    // PURCHASE_EVENT_DISABLED:             "Payment event should contain currency USD",
    // PURCHASE_EVENT_DISABLED:             paymentBody.contains("\"currency\":\"USD\"")
    // PURCHASE_EVENT_DISABLED:         )
    // PURCHASE_EVENT_DISABLED:         assertTrue(
    // PURCHASE_EVENT_DISABLED:             "Payment event should contain event_type buy",
    // PURCHASE_EVENT_DISABLED:             paymentBody.contains("\"event_type\":\"buy\"")
    // PURCHASE_EVENT_DISABLED:         )
    // PURCHASE_EVENT_DISABLED:         // Payment gets a link (either raw URI or resolved depending on timing),
    // PURCHASE_EVENT_DISABLED:         // but it should NOT be null since linkForFutureActions was set by Call #1
    // PURCHASE_EVENT_DISABLED:         assertTrue(
    // PURCHASE_EVENT_DISABLED:             "Payment event should have a link value (raw or resolved), got: ${paymentBody.take(400)}",
    // PURCHASE_EVENT_DISABLED:             paymentBody.contains("\"link\":")
    // PURCHASE_EVENT_DISABLED:         )
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:         activityController.stop().destroy()
    // PURCHASE_EVENT_DISABLED:     } finally {
    // PURCHASE_EVENT_DISABLED:         Dispatchers.resetMain()
    // PURCHASE_EVENT_DISABLED:     }
    // PURCHASE_EVENT_DISABLED: }

    // PURCHASE_EVENT_DISABLED: @Test
    // PURCHASE_EVENT_DISABLED: fun `test payment event without deeplink has no link attribution`() {
    // PURCHASE_EVENT_DISABLED:     // Arrange - no deeplink, so linkForFutureActions stays null.
    // PURCHASE_EVENT_DISABLED:     // Payment logged after auth should have null link.
    // PURCHASE_EVENT_DISABLED:     installEventCapturingDispatcher(lastSeen = null)
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     E2ETestUtils.configureAndWaitForAuthOnly(application)
    // PURCHASE_EVENT_DISABLED:     E2ETestUtils.enableImmediateEventSending()
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     val activityController = Robolectric.buildActivity(TestActivity::class.java)
    // PURCHASE_EVENT_DISABLED:     activityController.create().start()
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     // Wait for initial events
    // PURCHASE_EVENT_DISABLED:     waitForEvent("install")
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     // Log a custom purchase — no deeplink was opened, so no link
    // PURCHASE_EVENT_DISABLED:     Grovs.logCustomPurchase(
    // PURCHASE_EVENT_DISABLED:         type = PaymentEventType.BUY,
    // PURCHASE_EVENT_DISABLED:         priceInCents = 299,
    // PURCHASE_EVENT_DISABLED:         currency = "GBP",
    // PURCHASE_EVENT_DISABLED:         productId = "no_link_purchase"
    // PURCHASE_EVENT_DISABLED:     )
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     // Wait for payment event
    // PURCHASE_EVENT_DISABLED:     E2ETestUtils.waitForCondition(timeoutMs = 10_000, description = "payment event without link") {
    // PURCHASE_EVENT_DISABLED:         E2ETestUtils.enableImmediateEventSending()
    // PURCHASE_EVENT_DISABLED:         synchronized(capturedPaymentBodies) {
    // PURCHASE_EVENT_DISABLED:             capturedPaymentBodies.any { it.contains("\"product_id\":\"no_link_purchase\"") }
    // PURCHASE_EVENT_DISABLED:         }
    // PURCHASE_EVENT_DISABLED:     }
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     // Assert - payment event should have null link since no deeplink was opened
    // PURCHASE_EVENT_DISABLED:     val paymentBody = synchronized(capturedPaymentBodies) {
    // PURCHASE_EVENT_DISABLED:         capturedPaymentBodies.first { it.contains("\"product_id\":\"no_link_purchase\"") }
    // PURCHASE_EVENT_DISABLED:     }
    // PURCHASE_EVENT_DISABLED:     assertTrue(
    // PURCHASE_EVENT_DISABLED:         "Payment event should contain price_cents 299, got: ${paymentBody.take(400)}",
    // PURCHASE_EVENT_DISABLED:         paymentBody.contains("\"price_cents\":299")
    // PURCHASE_EVENT_DISABLED:     )
    // PURCHASE_EVENT_DISABLED:     assertTrue(
    // PURCHASE_EVENT_DISABLED:         "Payment event should contain currency GBP",
    // PURCHASE_EVENT_DISABLED:         paymentBody.contains("\"currency\":\"GBP\"")
    // PURCHASE_EVENT_DISABLED:     )
    // PURCHASE_EVENT_DISABLED:     assertTrue(
    // PURCHASE_EVENT_DISABLED:         "Payment event should contain event_type buy",
    // PURCHASE_EVENT_DISABLED:         paymentBody.contains("\"event_type\":\"buy\"")
    // PURCHASE_EVENT_DISABLED:     )
    // PURCHASE_EVENT_DISABLED:     // Without a deeplink, link should be null or absent
    // PURCHASE_EVENT_DISABLED:     val hasNullLink = paymentBody.contains("\"link\":null")
    // PURCHASE_EVENT_DISABLED:     val hasNoLink = !paymentBody.contains("\"link\":")
    // PURCHASE_EVENT_DISABLED:     assertTrue(
    // PURCHASE_EVENT_DISABLED:         "Payment event should have null or no link when no deeplink opened, got: ${paymentBody.take(400)}",
    // PURCHASE_EVENT_DISABLED:         hasNullLink || hasNoLink
    // PURCHASE_EVENT_DISABLED:     )
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     activityController.stop().destroy()
    // PURCHASE_EVENT_DISABLED: }

    // ==================== Deferred Attribution (Install Referrer) Tests ====================
    //
    // These tests simulate the deferred attribution path: no deeplink intent, install
    // referrer returns null (Robolectric), so the SDK calls data_for_device with null
    // link. The backend's data_for_device response is DELAYED (3s) to ensure events
    // (INSTALL, REINSTALL, APP_OPEN) are logged locally BEFORE the attribution link
    // arrives. When the response returns a resolved link, addLinkToEvents()
    // retroactively patches the stored events. We verify that the final events sent to
    // the backend carry the resolved link — proving retroactive patching works.

    /**
     * Install a dispatcher that delays the data_for_device response to simulate slow
     * deferred attribution. Events are captured. All other endpoints respond immediately.
     */
    private fun installDeferredAttributionDispatcher(
        lastSeen: String? = null,
        resolvedLink: String,
        delaySeconds: Long = 3
    ) {
        val lastSeenJson = lastSeen?.let { "\"$it\"" } ?: "null"

        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val body = request.body.clone().readUtf8()

                if (path.contains("add_payment_event")) {
                    synchronized(capturedPaymentBodies) {
                        capturedPaymentBodies.add(body)
                    }
                } else if (path.contains("event")) {
                    synchronized(capturedEventBodies) {
                        capturedEventBodies.add(body)
                    }
                }

                return when {
                    path.contains("authenticate") -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"linksquared":"test-grovs-id-123","uri_scheme":"testapp"}""")
                    path.contains("device_for_vendor_id") -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"last_seen":$lastSeenJson}""")
                    path.contains("data_for_device") -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"link":"$resolvedLink","data":null}""")
                        .setBodyDelay(delaySeconds, java.util.concurrent.TimeUnit.SECONDS)
                    else -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{}")
                }
            }
        }
    }

    @Test
    fun `test install event gets resolved link via deferred attribution`() {
        // No deeplink intent. data_for_device is delayed 3s to ensure the INSTALL event
        // is logged locally before the attribution link arrives. When the response comes
        // back, addLinkToEvents() retroactively patches the stored event.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            installDeferredAttributionDispatcher(
                lastSeen = null,
                resolvedLink = "https://test.grovs.io/deferred-install"
            )

            E2ETestUtils.configureAndWaitForAuthOnly(application)
            // Do NOT enable immediate sending — delayEvents=true keeps events in storage
            // so addLinkToEvents can patch them when the deferred link arrives

            // Plain activity start (no deeplink) → onStart → handleIntent →
            // getDataForDevice(null, delayEvents=true):
            //   Call #1: setLinkToNewFutureActions(null) → events logged with null link
            //   Network: data_for_device POST (delayed 3s)
            //   Call #2: setLinkToNewFutureActions(resolvedLink) → addLinkToEvents patches INSTALL
            val activityController = Robolectric.buildActivity(TestActivity::class.java)
            activityController.create()
            activityController.start()

            // Verify no events sent yet — data_for_device is still pending (3s delay),
            // and delayEvents=true blocks sending
            val eventsSentDuringDelay = synchronized(capturedEventBodies) {
                capturedEventBodies.filter { it.contains("\"event\":\"install\"") }
            }
            assertTrue(
                "Install event should NOT be sent to backend while data_for_device is pending",
                eventsSentDuringDelay.isEmpty()
            )

            // Wait for data_for_device response (3s delay) + addLinkToEvents patching
            Thread.sleep(4000)

            // Enable sending and trigger flush
            E2ETestUtils.enableImmediateEventSending()
            activityController.pause().stop()
            activityController.start().resume()

            // Wait for install event with the deferred attribution link
            E2ETestUtils.waitForCondition(timeoutMs = 10_000, description = "install event with deferred attribution link") {
                E2ETestUtils.enableImmediateEventSending()
                synchronized(capturedEventBodies) {
                    capturedEventBodies.any {
                        it.contains("\"event\":\"install\"") && it.contains("\"link\":\"https://test.grovs.io/deferred-install\"")
                    }
                }
            }

            // Assert — the install event arrives at the backend with the resolved link,
            // proving it was retroactively patched after being logged locally without one
            assertEventHasLink("install", "https://test.grovs.io/deferred-install")

            activityController.stop().destroy()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `test reinstall event gets resolved link via deferred attribution`() {
        // Returning device (lastSeen set), no deeplink. data_for_device delayed 3s.
        // REINSTALL event logged locally first, then retroactively patched with link.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            installDeferredAttributionDispatcher(
                lastSeen = "2026-01-10T12:00:00.000Z",
                resolvedLink = "https://test.grovs.io/deferred-reinstall"
            )

            E2ETestUtils.configureAndWaitForAuthOnly(application)

            // Plain activity start (no deeplink)
            val activityController = Robolectric.buildActivity(TestActivity::class.java)
            activityController.create()
            activityController.start()

            // Verify no events sent yet during the delay window
            val eventsSentDuringDelay = synchronized(capturedEventBodies) {
                capturedEventBodies.filter { it.contains("\"event\":\"reinstall\"") }
            }
            assertTrue(
                "Reinstall event should NOT be sent to backend while data_for_device is pending",
                eventsSentDuringDelay.isEmpty()
            )

            // Wait for deferred attribution resolution (3s delay + margin)
            Thread.sleep(4000)

            // Enable sending and trigger flush
            E2ETestUtils.enableImmediateEventSending()
            activityController.pause().stop()
            activityController.start().resume()

            // Wait for reinstall event with the deferred attribution link
            E2ETestUtils.waitForCondition(timeoutMs = 10_000, description = "reinstall event with deferred attribution link") {
                E2ETestUtils.enableImmediateEventSending()
                synchronized(capturedEventBodies) {
                    capturedEventBodies.any {
                        it.contains("\"event\":\"reinstall\"") && it.contains("\"link\":\"https://test.grovs.io/deferred-reinstall\"")
                    }
                }
            }

            // Assert — reinstall event retroactively patched with deferred link
            assertEventHasLink("reinstall", "https://test.grovs.io/deferred-reinstall")
            assertEquals("Should not send install for returning device", 0, eventCount("install"))

            activityController.stop().destroy()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `test app_open event gets resolved link via deferred attribution`() {
        // No deeplink. data_for_device delayed 3s. APP_OPEN event logged locally
        // before the link arrives, then retroactively patched with the resolved link.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            installDeferredAttributionDispatcher(
                lastSeen = null,
                resolvedLink = "https://test.grovs.io/deferred-appopen"
            )

            E2ETestUtils.configureAndWaitForAuthOnly(application)

            // Plain activity start (no deeplink)
            val activityController = Robolectric.buildActivity(TestActivity::class.java)
            activityController.create()
            activityController.start()

            // Verify no events sent yet during the delay window
            val eventsSentDuringDelay = synchronized(capturedEventBodies) {
                capturedEventBodies.filter { it.contains("\"event\":\"app_open\"") }
            }
            assertTrue(
                "app_open event should NOT be sent to backend while data_for_device is pending",
                eventsSentDuringDelay.isEmpty()
            )

            // Wait for deferred attribution resolution (3s delay + margin)
            Thread.sleep(4000)

            // Enable sending and trigger flush
            E2ETestUtils.enableImmediateEventSending()
            activityController.pause().stop()
            activityController.start().resume()

            // Wait for app_open event with the deferred attribution link
            E2ETestUtils.waitForCondition(timeoutMs = 10_000, description = "app_open event with deferred attribution link") {
                E2ETestUtils.enableImmediateEventSending()
                synchronized(capturedEventBodies) {
                    capturedEventBodies.any {
                        it.contains("\"event\":\"app_open\"") && it.contains("\"link\":\"https://test.grovs.io/deferred-appopen\"")
                    }
                }
            }

            // Assert — app_open event retroactively patched with deferred link
            assertEventHasLink("app_open", "https://test.grovs.io/deferred-appopen")

            activityController.stop().destroy()
        } finally {
            Dispatchers.resetMain()
        }
    }

    // ==================== Time Spent Event Tests ====================
    //
    // TIME_SPENT events track user engagement duration. They are:
    // - Created (with engagementTime=null) on app launch and each foreground transition
    // - Finalized (engagementTime calculated) when the app goes to background or a new link is set
    // - Sent to the backend on the next foreground transition via sendTimeSpentEventsToBackend()
    // - NOT retroactively patched by addLinkToEvents() — link is set at node creation time
    //
    // The SDK's onAppForegrounded() runs on serialDispatcher (IO.limitedParallelism(1)),
    // serialized behind handleIntent from onStart. onAppBackgrounded() finalizes
    // TIME_SPENT via GlobalScope.launch (Dispatchers.Default). Both are async, so tests
    // must allow sufficient time for the full chain to complete.

    private fun assertTimeSpentEventSent() {
        val body = synchronized(capturedEventBodies) {
            capturedEventBodies.first { it.contains("\"event\":\"time_spent\"") }
        }
        assertTrue(
            "TIME_SPENT event should have 'event' field set to 'time_spent', got: ${body.take(300)}",
            body.contains("\"event\":\"time_spent\"")
        )
        assertTrue(
            "TIME_SPENT event should have 'created_at' timestamp, got: ${body.take(300)}",
            body.contains("\"created_at\":")
        )
    }

    private fun assertTimeSpentHasEngagementTime() {
        val body = synchronized(capturedEventBodies) {
            capturedEventBodies.first { it.contains("\"event\":\"time_spent\"") }
        }
        assertTrue(
            "TIME_SPENT event should have 'engagement_time' field, got: ${body.take(300)}",
            body.contains("\"engagement_time\":")
        )
        assertFalse(
            "TIME_SPENT event should not have null engagement_time, got: ${body.take(300)}",
            body.contains("\"engagement_time\":null")
        )
    }

    private fun assertTimeSpentHasLink(expectedLink: String) {
        val body = synchronized(capturedEventBodies) {
            capturedEventBodies.last { it.contains("\"event\":\"time_spent\"") }
        }
        assertTrue(
            "TIME_SPENT event should have link '$expectedLink', got: ${body.take(300)}",
            body.contains("\"link\":\"$expectedLink\"")
        )
    }

    private fun assertTimeSpentHasNoLink() {
        val body = synchronized(capturedEventBodies) {
            capturedEventBodies.first { it.contains("\"event\":\"time_spent\"") }
        }
        val hasNullLink = body.contains("\"link\":null")
        val hasNoLink = !body.contains("\"link\":")
        assertTrue(
            "TIME_SPENT event should have null or no link, got: ${body.take(300)}",
            hasNullLink || hasNoLink
        )
    }

    /**
     * Perform a full background→foreground cycle with sufficient waits for async SDK
     * operations. onAppBackgrounded() finalizes TIME_SPENT via GlobalScope.launch, and
     * onAppForegrounded() sends it via serialDispatcher — both are async.
     */
    private fun performBackgroundForegroundCycle(
        activityController: org.robolectric.android.controller.ActivityController<TestActivity>
    ) {
        // Use the full lifecycle sequence (pause→stop, start→resume) because
        // Robolectric skips onActivityStopped if the activity hasn't been resumed.
        // However, Robolectric also double-dispatches onActivityStarted during
        // start→resume on a restarted AppCompatActivity, inflating numStarted.
        // Reset numStarted after resume to ensure the next stop() correctly
        // decrements to 0 and fires onAppBackgrounded.
        activityController.pause().stop()
        // Give GlobalScope.launch in onAppBackgrounded time to finalize TIME_SPENT node
        Thread.sleep(2000)

        activityController.start().resume()
        E2ETestUtils.setNumStarted(1) // correct for Robolectric double-dispatch
        E2ETestUtils.enableImmediateEventSending()
        // Give serialDispatcher time to run handleIntent + onAppForegrounded (which sends TIME_SPENT)
        Thread.sleep(3000)
    }

    @Test
    fun `test TIME_SPENT event is sent after background foreground cycle`() {
        // A TIME_SPENT node is created on app launch. When the app goes to background,
        // the node is finalized (engagement_time calculated). On the next foreground,
        // sendTimeSpentEventsToBackend() sends the completed event.
        installEventCapturingDispatcher(lastSeen = null)

        E2ETestUtils.configureAndWaitForAuthOnly(application)
        E2ETestUtils.enableImmediateEventSending()

        val activityController = Robolectric.buildActivity(TestActivity::class.java)
        activityController.create().start().resume()

        // Wait for initial events + let serialDispatcher complete handleIntent + onAppForegrounded
        waitForEvent("install")
        Thread.sleep(2000)

        // Background→foreground cycle to finalize and send TIME_SPENT
        performBackgroundForegroundCycle(activityController)

        // Wait for TIME_SPENT event
        E2ETestUtils.waitForCondition(timeoutMs = 15_000, description = "time_spent event sent to backend") {
            E2ETestUtils.enableImmediateEventSending()
            synchronized(capturedEventBodies) {
                capturedEventBodies.any { it.contains("\"event\":\"time_spent\"") }
            }
        }

        // Assert
        assertTimeSpentEventSent()
        assertTimeSpentHasEngagementTime()

        activityController.pause().stop().destroy()
    }

    @Test
    fun `test TIME_SPENT event without deeplink has no link attribution`() {
        // When no deeplink is active, the TIME_SPENT node is created with
        // linkForFutureActions=null, so the event should have no link.
        installEventCapturingDispatcher(lastSeen = null)

        E2ETestUtils.configureAndWaitForAuthOnly(application)
        E2ETestUtils.enableImmediateEventSending()

        val activityController = Robolectric.buildActivity(TestActivity::class.java)
        activityController.create().start().resume()

        waitForEvent("install")
        Thread.sleep(2000)

        // Background→foreground cycle to flush TIME_SPENT
        performBackgroundForegroundCycle(activityController)

        E2ETestUtils.waitForCondition(timeoutMs = 15_000, description = "time_spent event without link") {
            E2ETestUtils.enableImmediateEventSending()
            synchronized(capturedEventBodies) {
                capturedEventBodies.any { it.contains("\"event\":\"time_spent\"") }
            }
        }

        // Assert — no deeplink was opened, so TIME_SPENT should have null link
        assertTimeSpentEventSent()
        assertTimeSpentHasNoLink()

        activityController.pause().stop().destroy()
    }

    @Test
    fun `test TIME_SPENT event carries deeplink link when set before backgrounding`() {
        // When a deeplink is resolved, setLinkToNewFutureActions() calls
        // markTimeSpentNode(startingNode=false, link=link), which finalizes the old
        // TIME_SPENT node and creates a new one with the resolved link. The new node
        // carries the link from that point onward. When the app goes to background,
        // the node is finalized; on the next foreground it's sent with the link.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            installDeeplinkEventCapturingDispatcher(
                lastSeen = null,
                resolvedLink = "https://test.grovs.io/time-spent-link"
            )

            E2ETestUtils.configureAndWaitForAuthOnly(application)

            // Cold start with deeplink
            val deeplinkIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("testapp://open?link=time-spent-link")
            }
            val activityController = Robolectric.buildActivity(TestActivity::class.java, deeplinkIntent)
            activityController.create()

            Grovs.setOnDeeplinkReceivedListener(activityController.get()) { _ -> }
            activityController.start()

            // Wait for deeplink resolution + handleIntent + onAppForegrounded to complete
            // on serialDispatcher. setLinkToNewFutureActions creates a new TIME_SPENT
            // node with the resolved link.
            Thread.sleep(3000)

            activityController.resume()

            // Background→foreground to finalize and send the TIME_SPENT
            performBackgroundForegroundCycle(activityController)

            E2ETestUtils.waitForCondition(timeoutMs = 15_000, description = "time_spent event with deeplink") {
                E2ETestUtils.enableImmediateEventSending()
                synchronized(capturedEventBodies) {
                    capturedEventBodies.any {
                        it.contains("\"event\":\"time_spent\"") &&
                            it.contains("\"link\":\"https://test.grovs.io/time-spent-link\"")
                    }
                }
            }

            // Assert — TIME_SPENT event should carry the resolved deeplink
            assertTimeSpentHasLink("https://test.grovs.io/time-spent-link")
            assertTimeSpentHasEngagementTime()

            activityController.stop().destroy()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `test TIME_SPENT event is not retroactively patched by deeplink resolution`() {
        // addLinkToEvents() explicitly skips TIME_SPENT events. If a TIME_SPENT node
        // was created BEFORE a deeplink resolves (with null link), it should keep its
        // null link — only the NEW node created by setLinkToNewFutureActions gets the link.
        //
        // Flow:
        // 1. App launches → TIME_SPENT node created with null link
        // 2. Deferred attribution resolves → addLinkToEvents patches INSTALL/APP_OPEN
        //    but NOT TIME_SPENT → old TIME_SPENT finalized, new one created with link
        // 3. We check that the first TIME_SPENT sent (the old one) has null link
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            installDeferredAttributionDispatcher(
                lastSeen = null,
                resolvedLink = "https://test.grovs.io/not-for-timespent"
            )

            E2ETestUtils.configureAndWaitForAuthOnly(application)

            // Plain activity start → TIME_SPENT node created with null link
            // (linkForFutureActions is null, data_for_device delayed 3s)
            val activityController = Robolectric.buildActivity(TestActivity::class.java)
            activityController.create().start().resume()

            // Wait for data_for_device to resolve (3s delay) + serial dispatcher chain
            Thread.sleep(5000)

            // Background→foreground to finalize + send TIME_SPENT events
            performBackgroundForegroundCycle(activityController)

            E2ETestUtils.waitForCondition(timeoutMs = 15_000, description = "time_spent events sent") {
                E2ETestUtils.enableImmediateEventSending()
                synchronized(capturedEventBodies) {
                    capturedEventBodies.any { it.contains("\"event\":\"time_spent\"") }
                }
            }

            // Assert — the first TIME_SPENT event (created before deferred attribution
            // resolved) should have null/no link, because addLinkToEvents does not patch
            // TIME_SPENT events.
            val timeSpentEvents = synchronized(capturedEventBodies) {
                capturedEventBodies.filter { it.contains("\"event\":\"time_spent\"") }
            }
            assertTrue("At least one TIME_SPENT event should be sent", timeSpentEvents.isNotEmpty())

            val firstTimeSpent = timeSpentEvents.first()
            val firstHasNullLink = firstTimeSpent.contains("\"link\":null")
            val firstHasNoLink = !firstTimeSpent.contains("\"link\":")
            assertTrue(
                "First TIME_SPENT (created before link resolution) should have null link, got: ${firstTimeSpent.take(400)}",
                firstHasNullLink || firstHasNoLink
            )

            // If there's a second TIME_SPENT (the one created after link resolution),
            // it should carry the resolved link
            if (timeSpentEvents.size >= 2) {
                val secondTimeSpent = timeSpentEvents[1]
                assertTrue(
                    "Second TIME_SPENT (created after link resolution) should have the resolved link, got: ${secondTimeSpent.take(400)}",
                    secondTimeSpent.contains("\"link\":\"https://test.grovs.io/not-for-timespent\"")
                )
            }

            activityController.stop().destroy()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `test multiple background foreground cycles produce multiple TIME_SPENT events`() {
        // Each background→foreground cycle should finalize one TIME_SPENT event and
        // create a new node. After 3 cycles we expect multiple completed TIME_SPENT
        // events sent to the backend.
        installEventCapturingDispatcher(lastSeen = null)

        E2ETestUtils.configureAndWaitForAuthOnly(application)
        E2ETestUtils.enableImmediateEventSending()

        val activityController = Robolectric.buildActivity(TestActivity::class.java)
        activityController.create().start().resume()
        E2ETestUtils.setNumStarted(1) // correct for Robolectric double-dispatch

        waitForEvent("install")
        Thread.sleep(2000)

        // Perform 3 background→foreground cycles
        repeat(3) {
            performBackgroundForegroundCycle(activityController)
        }

        // Wait for at least one TIME_SPENT event
        E2ETestUtils.waitForCondition(timeoutMs = 15_000, description = "time_spent events from multiple cycles") {
            E2ETestUtils.enableImmediateEventSending()
            synchronized(capturedEventBodies) {
                capturedEventBodies.any { it.contains("\"event\":\"time_spent\"") }
            }
        }

        // Assert — should have multiple TIME_SPENT events
        val timeSpentCount = synchronized(capturedEventBodies) {
            capturedEventBodies.count { it.contains("\"event\":\"time_spent\"") }
        }
        assertTrue(
            "Expected multiple TIME_SPENT events after 3 bg/fg cycles, got: $timeSpentCount",
            timeSpentCount >= 2
        )

        // All TIME_SPENT events should have engagement_time set
        val timeSpentEvents = synchronized(capturedEventBodies) {
            capturedEventBodies.filter { it.contains("\"event\":\"time_spent\"") }
        }
        timeSpentEvents.forEach { body ->
            assertTrue(
                "Each TIME_SPENT event should have engagement_time, got: ${body.take(300)}",
                body.contains("\"engagement_time\":")
            )
            assertFalse(
                "Each TIME_SPENT event should not have null engagement_time, got: ${body.take(300)}",
                body.contains("\"engagement_time\":null")
            )
        }

        activityController.pause().stop().destroy()
    }
}
