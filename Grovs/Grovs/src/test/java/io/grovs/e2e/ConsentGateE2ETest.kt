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
}
