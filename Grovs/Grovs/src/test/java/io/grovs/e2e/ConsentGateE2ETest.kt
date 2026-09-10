package io.grovs.e2e

import android.app.Application
import io.grovs.Grovs
import io.grovs.handlers.GrovsManager
import io.grovs.storage.EventsStorage
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** The consent gate: a disabled SDK touches neither the network nor the events store. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConsentGateE2ETest {

    private lateinit var application: Application
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        E2ETestUtils.resetGrovsSingleton()
        E2ETestUtils.setupTestApplication(application)
        E2ETestUtils.setupMockGlInfo()
        E2ETestUtils.setupMockUserAgent("Grovs SDK consent tests")
        server = E2ETestUtils.createMockWebServer()
        E2ETestUtils.setUrlDispatcher(server, linkedMapOf(
            "authenticate" to json("""{"linksquared":"test-grovs-id-123","uri_scheme":"testapp"}"""),
            "device_for_vendor_id" to json("""{"last_seen":null}"""),
            "data_for_device" to json("""{"link":null,"data":null}"""),
            "events/batch" to json("""{"accepted":50,"rejected":0,"errors":[]}"""),
        ))
    }

    @After
    fun tearDown() {
        E2ETestUtils.cleanupMockWebServer(server)
        E2ETestUtils.resetGrovsSingleton()
    }

    private fun json(body: String) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json").setBody(body)

    private fun configure(enabled: Boolean) {
        Grovs.configure(application, "test-api-key", useTestEnvironment = true, baseURL = server.url("/").toString(),
            autoTrackScreenViews = true, clipboardDomains = null, enabled = enabled)
    }

    private fun storedEventTypes(): List<String> =
        runBlocking { EventsStorage(application).getEvents() }.map { it.event.name }

    private fun manager() = E2ETestUtils.getGrovsManager() as GrovsManager

    @Test
    fun `a disabled configure makes no request and writes no launch events`() {
        configure(enabled = false)
        E2ETestUtils.runWithLooperPumping(1_000) { E2ETestUtils.getAuthenticationJob()?.join() }

        assertNull("No request may leave the device while disabled", server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals(emptyList<String>(), storedEventTypes())
        assertEquals(GrovsManager.AuthenticationState.UNAUTHENTICATED, manager().authenticationState)
    }

    @Test
    fun `enabling a disabled SDK authenticates and records the launch once`() {
        configure(enabled = false)

        Grovs.setSDK(true)
        E2ETestUtils.runWithLooperPumping(10_000) { E2ETestUtils.getAuthenticationJob()?.join() }

        assertNotNull(E2ETestUtils.awaitRequestFor(server, "/api/v1/sdk/authenticate"))
        assertEquals(GrovsManager.AuthenticationState.AUTHENTICATED, manager().authenticationState)
        val types = storedEventTypes()
        assertEquals("INSTALL and APP_OPEN once each, got $types", 1, types.count { it == "INSTALL" })
        assertEquals(1, types.count { it == "APP_OPEN" })
    }

    @Test
    fun `enabling twice does not authenticate twice`() {
        configure(enabled = false)

        Grovs.setSDK(true)
        Grovs.setSDK(true)
        E2ETestUtils.runWithLooperPumping(10_000) { E2ETestUtils.getAuthenticationJob()?.join() }

        val authRequests = E2ETestUtils.collectAllRequests(server).count { it.first.contains("authenticate") }
        assertEquals(1, authRequests)
        assertEquals(1, storedEventTypes().count { it == "APP_OPEN" })
    }

    @Test
    fun `disabling during an authentication retry discards it and enabling starts over`() {
        // A 500 with a parseable error body does not throw: GrovsService.authenticate emits
        // GVRetryResult.Error and completes, it never reaches retryWhen. Force the transport itself
        // to fail instead, so the flow really throws, retryWhen actually retries, and the job parks
        // in its 5-second delay in AuthenticationState.RETRYING.
        //
        // DISCONNECT_AT_START is checked against Dispatcher.peek(), whose default implementation
        // (unrelated to our path-based dispatch()) always reports KEEP_OPEN, so it's a silent no-op
        // through a custom Dispatcher like ours. DISCONNECT_AFTER_REQUEST is instead checked against
        // the MockResponse dispatch() actually returns, so it reliably closes the socket with nothing
        // written back, which is what makes the client's authenticate call throw.
        E2ETestUtils.setUrlDispatcher(server, linkedMapOf(
            "authenticate" to MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
            "device_for_vendor_id" to json("""{"last_seen":null}"""),
            "data_for_device" to json("""{"link":null,"data":null}"""),
            "events/batch" to json("""{"accepted":50,"rejected":0,"errors":[]}"""),
        ))
        configure(enabled = true)
        E2ETestUtils.waitForCondition(5_000, "authentication to reach its retry delay") {
            manager().authenticationState == GrovsManager.AuthenticationState.RETRYING
        }

        Grovs.setSDK(false)
        val cancelled = E2ETestUtils.getAuthenticationJob()
        E2ETestUtils.runWithLooperPumping(2_000) { cancelled?.join() }
        assertTrue("Disable must cancel the in-flight authentication", cancelled?.isCancelled == true)
        assertEquals(emptyList<String>(), storedEventTypes())

        // Backend recovers; enabling must authenticate afresh and log the launch exactly once.
        E2ETestUtils.setUrlDispatcher(server, linkedMapOf(
            "authenticate" to json("""{"linksquared":"test-grovs-id-123","uri_scheme":"testapp"}"""),
            "device_for_vendor_id" to json("""{"last_seen":null}"""),
            "data_for_device" to json("""{"link":null,"data":null}"""),
            "events/batch" to json("""{"accepted":50,"rejected":0,"errors":[]}"""),
        ))
        Grovs.setSDK(true)
        E2ETestUtils.runWithLooperPumping(10_000) { E2ETestUtils.getAuthenticationJob()?.join() }

        assertEquals(GrovsManager.AuthenticationState.AUTHENTICATED, manager().authenticationState)
        assertEquals(1, storedEventTypes().count { it == "APP_OPEN" })
    }

    // End-to-end: nothing leaves the device and nothing lands in storage while disabled, through a
    // full activity lifecycle. Coverage for each individual gate this exercises (lifecycle
    // bookkeeping, notification auto-display, auto screen tracking) lives in GrovsManagerTest,
    // NotificationsManagerTest and GrovsSingletonTest respectively.
    @Test
    fun `a disabled SDK sends and stores nothing through a full activity lifecycle`() {
        configure(enabled = false)
        val controller = org.robolectric.Robolectric.buildActivity(TestActivity::class.java)
        controller.create().start().resume().pause().stop()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals(emptyList<String>(), storedEventTypes())
        assertTrue(runBlocking { io.grovs.storage.CustomEventsStorage(application).getEvents() }.isEmpty())
        controller.destroy()
    }

    @Test
    fun `re-configuring while retrying stops the replaced manager's chained job from sending anything`() {
        // Every authenticate attempt disconnects, so both the original and the replacement manager
        // sit in AuthenticationState.RETRYING for as long as the test lets them. See the comment in
        // the retry-discard test above for why DISCONNECT_AFTER_REQUEST, not DISCONNECT_AT_START.
        E2ETestUtils.setUrlDispatcher(server, linkedMapOf(
            "authenticate" to MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
            "device_for_vendor_id" to json("""{"last_seen":null}"""),
            "data_for_device" to json("""{"link":null,"data":null}"""),
            "events/batch" to json("""{"accepted":50,"rejected":0,"errors":[]}"""),
        ))
        configure(enabled = true)
        E2ETestUtils.waitForCondition(5_000, "the first manager to reach its retry delay") {
            manager().authenticationState == GrovsManager.AuthenticationState.RETRYING
        }

        // Re-configure while the old manager's job is parked in its retry delay: checkConfiguration
        // chains the new job onto it, so unless the old job is explicitly cancelled, it keeps
        // retrying against a manager that configure() just replaced.
        configure(enabled = true)
        E2ETestUtils.waitForCondition(5_000, "the replacement manager to reach its retry delay") {
            manager().authenticationState == GrovsManager.AuthenticationState.RETRYING
        }

        Grovs.setSDK(false)
        E2ETestUtils.collectAllRequests(server) // drain whatever both managers already sent

        val deadline = System.currentTimeMillis() + 7_000 // longer than one 5s retry interval
        while (System.currentTimeMillis() < deadline) {
            val request = server.takeRequest(500, TimeUnit.MILLISECONDS) ?: continue
            val path = request.path.orEmpty()
            assertTrue(
                "No authenticate or device_for_vendor_id request may arrive once disabled, got $path",
                !path.contains("authenticate") && !path.contains("device_for_vendor_id")
            )
        }
    }

    // Extracts the "event_name" of every custom event across a set of raw (path, body) requests.
    // System events carry "event" instead, so they never appear here.
    private fun customEventNames(requests: List<Pair<String, String>>): List<String> =
        E2ETestUtils.eventsFromBatchRequests(requests).mapNotNull { it.optString("event_name", null) }

    // The brief's version of this test flushes `before_disable` to a 200 before the disable, which
    // sends it rather than leaving it queued — that can't demonstrate "queued before a disable,
    // delivered on enable". Instead: make events/batch answer 503 so the pre-disable track() is
    // attempted and kept (not delivered), disable and prove nothing leaves while disabled, then swap
    // the endpoint back to 200 and prove enabling flushes exactly the queued event and nothing the
    // disabled period tried to add.
    @Test
    fun `disable then enable on an authenticated SDK flushes what was queued before the disable`() {
        E2ETestUtils.setUrlDispatcher(server, linkedMapOf(
            "authenticate" to json("""{"linksquared":"test-grovs-id-123","uri_scheme":"testapp"}"""),
            "device_for_vendor_id" to json("""{"last_seen":null}"""),
            "data_for_device" to json("""{"link":null,"data":null}"""),
            "events/batch" to MockResponse().setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"events backend unavailable"}"""),
        ))
        configure(enabled = true)
        E2ETestUtils.runWithLooperPumping(10_000) { E2ETestUtils.getAuthenticationJob()?.join() }
        assertNotNull(E2ETestUtils.awaitRequestFor(server, "/api/v1/sdk/authenticate"))

        val allRequests = mutableListOf<Pair<String, String>>()

        // Attempt the flush against a 503'd endpoint: it must be tried (proving delivery was really
        // attempted, not skipped) and kept queued (proving a failed send does not drop it).
        Grovs.track("before_disable")
        E2ETestUtils.flushCustomEvents()
        allRequests += E2ETestUtils.collectAllRequests(server)
        assertTrue(
            "before_disable must have been attempted against the failing endpoint, got ${customEventNames(allRequests)}",
            "before_disable" in customEventNames(allRequests)
        )

        Grovs.setSDK(false)
        Grovs.track("while_disabled") // dropped: CustomEventsManager.track() no-ops while disabled
        E2ETestUtils.flushCustomEvents() // returns early while disabled; nothing sent

        // Drain anything in flight into its own list first: if the flush() consent gate were
        // missing, the disabled-phase flush would actually POST, and folding the drain straight
        // into allRequests would let the later "before_disable must be delivered" assertion pass
        // off this leaked request instead of the one from re-enabling.
        val whileDisabledRequests = E2ETestUtils.collectAllRequests(server)
        allRequests += whileDisabledRequests
        assertTrue(
            "No custom-events batch may leave the device while disabled, got " +
                whileDisabledRequests.filter { it.first.contains("/api/v1/sdk/events/batch") },
            whileDisabledRequests.none { it.first.contains("/api/v1/sdk/events/batch") }
        )
        assertNull("No request may leave the device while disabled", server.takeRequest(500, TimeUnit.MILLISECONDS))

        // Backend recovers.
        E2ETestUtils.setUrlDispatcher(server, linkedMapOf(
            "authenticate" to json("""{"linksquared":"test-grovs-id-123","uri_scheme":"testapp"}"""),
            "device_for_vendor_id" to json("""{"last_seen":null}"""),
            "data_for_device" to json("""{"link":null,"data":null}"""),
            "events/batch" to json("""{"accepted":50,"rejected":0,"errors":[]}"""),
        ))

        Grovs.setSDK(true)

        // Poll requests arriving from this point on, in a list of their own: allRequests already
        // contains the 503'd before_disable batch from the pre-disable attempt above, so checking
        // "before_disable" against allRequests here would pass vacuously off that stale batch
        // without proving enable actually resent anything. Don't assume the first batch after
        // enable is the custom one either: onEnabled() also flushes system events to the same
        // endpoint.
        val afterEnableRequests = mutableListOf<Pair<String, String>>()
        val deadline = System.currentTimeMillis() + 5_000
        var flushed = false
        while (System.currentTimeMillis() < deadline && !flushed) {
            val request = server.takeRequest(200, TimeUnit.MILLISECONDS)
            if (request != null) {
                afterEnableRequests += Pair(request.path.orEmpty(), request.body.readUtf8())
            }
            flushed = "before_disable" in customEventNames(afterEnableRequests)
        }
        assertTrue(
            "Enable must flush before_disable within 5s; custom events seen after enable so far: " +
                "${customEventNames(afterEnableRequests)}",
            flushed
        )

        // Drain whatever else is still in flight before making the final, whole-test assertion.
        afterEnableRequests += E2ETestUtils.collectAllRequests(server)
        allRequests += afterEnableRequests
        val allCustomNames = customEventNames(allRequests)
        assertTrue("before_disable must be delivered, got $allCustomNames", "before_disable" in allCustomNames)
        assertTrue(
            "while_disabled must never appear in any batch seen in this test, got $allCustomNames",
            "while_disabled" !in allCustomNames
        )
    }
}
