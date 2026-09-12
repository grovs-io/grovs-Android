package io.grovs.e2e

import android.app.Application
import android.os.Looper
import io.grovs.Grovs
import io.grovs.service.ConsentRequestExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch

/**
 * E2E tests for notifications functionality.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationsE2ETest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var application: Application

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
        // Deterministic retry timing for the server-error test below, which waits out the bounded
        // retry schedule in real time. It is a global seam, so teardown restores it.
        ConsentRequestExecutor.retryJitterMs = { 0 }
    }

    @After
    fun tearDown() {
        ConsentRequestExecutor.retryJitterMs = ConsentRequestExecutor.defaultJitterMs
        E2ETestUtils.cleanupMockWebServer(mockWebServer)
    }

    private suspend fun configureAndWaitForAuth() {
        E2ETestUtils.configureAndWaitForAuth(application, baseURL = mockWebServer.url("/").toString())
    }

    private fun json(body: String) =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private suspend fun configureWithUnreadResponse(response: MockResponse) {
        // Route by endpoint so concurrent startup requests cannot consume the unread-count reply.
        E2ETestUtils.setUrlDispatcher(mockWebServer, mapOf(
            "authenticate" to json("""{"linksquared":"test-grovs-id-123","uri_scheme":"testapp"}"""),
            "device_for_vendor_id" to json("""{"last_seen":null}"""),
            "data_for_device" to json("""{"link":null,"data":null}"""),
            "events/batch" to json("""{"accepted":50,"rejected":0,"errors":[]}"""),
            "clipboard_status" to json("""{"clipboard_active":false}"""),
            "number_of_unread_notifications" to response,
        ))
        configureAndWaitForAuth()
        E2ETestUtils.assertAuthenticationCompleted()
    }

    // ==================== Notifications Tests ====================

    @Test
    fun `Set automatic notifications listener does not crash`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)

            Grovs.configure(application, "test-api-key", useTestEnvironment = true, baseURL = mockWebServer.url("/").toString())

            val authJob = E2ETestUtils.getAuthenticationJob()
            withTimeoutOrNull(10_000) { authJob?.join() }

            // Act - set listener (test verifies this doesn't crash)
            Grovs.setOnAutomaticNotificationsListener { isLast ->
                // Listener set successfully - callback would be invoked when notifications arrive
            }

            // Assert
            E2ETestUtils.assertAuthenticationCompleted()
            val requests = E2ETestUtils.collectAllRequests(mockWebServer)
            E2ETestUtils.assertRequestMade(requests, "authenticate", "SDK should call authenticate endpoint")
            E2ETestUtils.assertRequestMade(requests, "device_for_vendor_id", "SDK should call device_for_vendor_id endpoint")

            val instance = E2ETestUtils.getGrovsInstance()
            assertNotNull("Grovs instance should exist", instance)
            E2ETestUtils.assertSdkFunctionalAfterError()
        }
    }

    @Test
    fun `Display messages fragment does not crash without activity`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)

            Grovs.configure(application, "test-api-key", useTestEnvironment = true, baseURL = mockWebServer.url("/").toString())

            val authJob = E2ETestUtils.getAuthenticationJob()
            withTimeoutOrNull(10_000) { authJob?.join() }

            // Act
            var callbackInvoked = false
            Grovs.displayMessagesFragment {
                callbackInvoked = true
            }

            // Assert
            E2ETestUtils.assertAuthenticationCompleted()
            val requests = E2ETestUtils.collectAllRequests(mockWebServer)
            E2ETestUtils.assertRequestMade(requests, "authenticate", "SDK should call authenticate endpoint")
            E2ETestUtils.assertSdkFunctionalAfterError()
        }
    }

    @Test
    fun `Number of unread messages with callback style API works`() {
        runBlocking {
            configureWithUnreadResponse(json("""{"number_of_unread_notifications":3}"""))

            // Act
            var callbackInvoked = false
            var callbackResult: Int? = null
            val latch = CountDownLatch(1)

            Grovs.numberOfUnreadMessages(lifecycleOwner = null) { result ->
                callbackInvoked = true
                callbackResult = result
                latch.countDown()
            }

            // Wait for callback with looper pumping
            val startTime = System.currentTimeMillis()
            while (latch.count > 0 && System.currentTimeMillis() - startTime < 5000) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(50)
            }

            // Assert
            E2ETestUtils.assertAuthenticationCompleted()
            val requests = E2ETestUtils.collectAllRequests(mockWebServer)
            E2ETestUtils.assertRequestMade(requests, "authenticate", "SDK should call authenticate endpoint")

            // Verify callback was actually invoked
            assertTrue("Callback should be invoked within timeout", callbackInvoked)

            // Verify the mocked unread count (3) is returned in callback
            assertNotNull("Callback should receive unread count", callbackResult)
            assertEquals("Callback should receive mocked unread count", 3, callbackResult)
        }
    }

    @Test
    fun `numberOfUnreadMessages returns value after authentication`() = runBlocking {
        configureWithUnreadResponse(json("""{"number_of_unread_notifications":5}"""))

        val count = E2ETestUtils.runWithLooperPumping(10_000, failOnTimeout = true) { Grovs.numberOfUnreadMessages() }

        assertEquals("Should return the backend count", 5, count)
    }

    @Test
    fun `numberOfUnreadMessages handles server error gracefully`() = runBlocking {
        configureWithUnreadResponse(json("""{"error":"Server Error"}""").setResponseCode(500))

        // A 500 is now retried on the bounded schedule (2s + 4s + 8s, jitter pinned to 0 above)
        // before the executor gives up, so this needs real time past that whole budget.
        val count = E2ETestUtils.runWithLooperPumping(16_000, failOnTimeout = true) { Grovs.numberOfUnreadMessages() }

        assertNull("A backend error should return null", count)
        E2ETestUtils.assertRequestMade(
            E2ETestUtils.collectAllRequests(mockWebServer), "number_of_unread_notifications"
        )
        E2ETestUtils.assertAuthenticationCompleted()
    }

    @Test
    fun `numberOfUnreadMessages returns null when SDK not configured`() {
        runBlocking {
            // Arrange - do NOT configure SDK

            // Act
            val count = Grovs.numberOfUnreadMessages()

            // Assert
            assertNull("Should return null when SDK not configured", count)
        }
    }

    @Test
    fun `numberOfUnreadMessages returns zero for new user`() = runBlocking {
        configureWithUnreadResponse(json("""{"number_of_unread_notifications":0}"""))

        val count = E2ETestUtils.runWithLooperPumping(10_000, failOnTimeout = true) { Grovs.numberOfUnreadMessages() }

        assertEquals("Should return the backend count", 0, count)
    }

    @Test
    fun `displayMessagesFragment handles missing activity gracefully`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)

            Grovs.configure(application, "test-api-key", useTestEnvironment = true, baseURL = mockWebServer.url("/").toString())

            val authJob = E2ETestUtils.getAuthenticationJob()
            withTimeoutOrNull(5_000) { authJob?.join() }

            // Act
            Grovs.displayMessagesFragment(onDismissed = null)

            // Assert
            E2ETestUtils.assertAuthenticationCompleted()
        }
    }

    @Test
    fun `Auto-display handles empty notifications list gracefully`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueAutoDisplayNotificationsResponse(mockWebServer, emptyList())

            // Act
            configureAndWaitForAuth()

            delay(500)

            // Assert
            E2ETestUtils.assertAuthenticationCompleted()
        }
    }
}
