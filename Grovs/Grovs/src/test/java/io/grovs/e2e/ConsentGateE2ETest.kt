package io.grovs.e2e

import android.app.Application
import io.grovs.Grovs
import io.grovs.handlers.GrovsManager
import io.grovs.storage.EventsStorage
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
        // First authenticate attempt fails so the SDK sits in its retry delay.
        E2ETestUtils.setUrlDispatcher(server, linkedMapOf(
            "authenticate" to MockResponse().setResponseCode(500).setBody("{}"),
            "device_for_vendor_id" to json("""{"last_seen":null}"""),
            "data_for_device" to json("""{"link":null,"data":null}"""),
            "events/batch" to json("""{"accepted":50,"rejected":0,"errors":[]}"""),
        ))
        configure(enabled = true)
        assertNotNull(E2ETestUtils.awaitRequestFor(server, "/api/v1/sdk/authenticate", timeoutMs = 5_000))

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
}
