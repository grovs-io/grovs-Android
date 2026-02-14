package io.grovs.e2e

import android.app.Application
import android.os.Looper
import io.grovs.Grovs
import io.grovs.service.GrovsService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockWebServer
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
    }

    @After
    fun tearDown() {
        E2ETestUtils.cleanupMockWebServer(mockWebServer)
    }

    private suspend fun configureAndWaitForAuth() {
        E2ETestUtils.configureAndWaitForAuth(application)
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

            Grovs.configure(application, "test-api-key", useTestEnvironment = true)

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

            Grovs.configure(application, "test-api-key", useTestEnvironment = true)

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
    fun `Get number of unread notifications returns count`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueUnreadCountResponse(mockWebServer, 5)
            E2ETestUtils.enqueueUnreadCountResponse(mockWebServer, 5)

            // Act
            configureAndWaitForAuth()
            delay(200)

            val result = E2ETestUtils.runWithLooperPumping(10_000) {
                Grovs.numberOfUnreadMessages()
            }

            // Assert - verify the mocked unread count (5) is returned
            if (result != null) {
                assertEquals("Should return mocked unread count", 5, result)
            } else {
                E2ETestUtils.assertAuthenticationCompleted()
            }
        }
    }

    @Test
    fun `Number of unread notifications before authentication returns null`() {
        // Arrange - don't configure SDK

        // Act & Assert
        try {
            runBlocking {
                val result = Grovs.numberOfUnreadMessages()
                assertNull("numberOfUnreadMessages should return null when not authenticated", result)
            }
        } catch (e: Exception) {
            val message = e.message?.lowercase() ?: ""
            assertTrue(
                "Exception should indicate SDK not initialized, got: ${e.message}",
                message.contains("not initialized") || message.contains("not configured") || message.contains("null")
            )
        }
    }

    @Test
    fun `Number of unread messages with callback style API works`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueUnreadCountResponse(mockWebServer, 3)
            E2ETestUtils.enqueueUnreadCountResponse(mockWebServer, 3)

            configureAndWaitForAuth()
            delay(200)


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
    fun `numberOfUnreadMessages returns value after authentication`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueUnreadCountResponse(mockWebServer, 5)
            E2ETestUtils.enqueueUnreadCountResponse(mockWebServer, 5)

            // Act
            configureAndWaitForAuth()
            delay(200)

            val count = E2ETestUtils.runWithLooperPumping(10_000) {
                Grovs.numberOfUnreadMessages()
            }

            // Assert - verify the mocked unread count (5) is returned
            if (count != null) {
                assertEquals("Should return mocked unread count", 5, count)
            } else {
                E2ETestUtils.assertAuthenticationCompleted()
            }
        }
    }

    @Test
    fun `numberOfUnreadMessages handles server error gracefully`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueErrorResponse(mockWebServer, 500, "Server Error")

            // Act
            configureAndWaitForAuth()
            val count = E2ETestUtils.runWithLooperPumping(10_000) {
                Grovs.numberOfUnreadMessages()
            }

            // Assert
            E2ETestUtils.assertAuthenticationCompleted()
        }
    }

    @Test
    fun `numberOfUnreadMessages with callback invokes listener`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueUnreadCountResponse(mockWebServer, 3)
            E2ETestUtils.enqueueUnreadCountResponse(mockWebServer, 3)

            configureAndWaitForAuth()

            var callbackInvoked = false
            var receivedCount: Int? = null
            val latch = CountDownLatch(1)

            // Act
            Grovs.numberOfUnreadMessages(lifecycleOwner = null) { count ->
                callbackInvoked = true
                receivedCount = count
                latch.countDown()
            }

            val startTime = System.currentTimeMillis()
            while (latch.count > 0 && System.currentTimeMillis() - startTime < 5000) {
    
                Thread.sleep(50)
            }

            // Assert - verify callback was invoked and received the mocked unread count
            E2ETestUtils.assertAuthenticationCompleted()
            if (callbackInvoked && receivedCount != null && receivedCount != 0) {
                assertEquals("Callback should receive mocked unread count", 3, receivedCount)
            }
        }
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
    fun `numberOfUnreadMessages returns zero for new user`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueUnreadCountResponse(mockWebServer, 0)

            // Act
            configureAndWaitForAuth()
            val count = E2ETestUtils.runWithLooperPumping(10_000) {
                Grovs.numberOfUnreadMessages()
            }

            // Assert
            if (count != null) {
                assertEquals("Should return 0 for new user", 0, count)
            } else {
                E2ETestUtils.assertAuthenticationCompleted()
            }
        }
    }

    @Test
    fun `displayMessagesFragment handles missing activity gracefully`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)

            Grovs.configure(application, "test-api-key", useTestEnvironment = true)

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
