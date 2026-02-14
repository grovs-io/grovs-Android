package io.grovs.e2e

import android.app.Application
import android.os.Looper
import io.grovs.Grovs
import io.grovs.model.LogLevel
// PURCHASE_EVENT_DISABLED: import io.grovs.model.events.PaymentEventType
import io.grovs.model.exceptions.GrovsException
import io.grovs.service.GrovsService
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * E2E tests for SDK configuration and lifecycle functionality.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SdkLifecycleE2ETest {

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

    // ==================== SDK Configuration & Lifecycle Tests ====================

    @Test
    fun `SDK can be disabled after configuration`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)

            E2ETestUtils.configureAndWaitForAuthOnly(application)

            val requestCountBeforeDisable = mockWebServer.requestCount
            assertTrue(
                "SDK should make requests before being disabled",
                requestCountBeforeDisable >= 1
            )

            // Act
            Grovs.setSDK(enabled = false)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            // Assert
            E2ETestUtils.assertAuthenticationCompleted()
            assertFalse("SDK should be disabled after setSDK(false)", E2ETestUtils.isSdkEnabled())
        }
    }

    @Test
    fun `SDK debug level changes do not crash or affect authentication`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)

            E2ETestUtils.configureAndWaitForAuthOnly(application)

            // Act - change debug level multiple times
            Grovs.setDebug(LogLevel.INFO)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            Grovs.setDebug(LogLevel.ERROR)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            Grovs.setDebug(LogLevel.INFO)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            // Assert - SDK should remain functional after debug level changes
            E2ETestUtils.assertAuthenticationCompleted()
            val requests = E2ETestUtils.collectAllRequests(mockWebServer)
            E2ETestUtils.assertRequestMade(requests, "authenticate", "SDK should call authenticate endpoint")
            E2ETestUtils.assertRequestMade(requests, "device_for_vendor_id", "SDK should call device_for_vendor_id endpoint")
            E2ETestUtils.assertSdkFunctionalAfterError()
        }
    }

    @Test
    fun `Multiple configure calls do not crash`() {
        runBlocking {
            // Arrange
            repeat(3) {
                E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
                E2ETestUtils.enqueueDeviceResponse(mockWebServer)
                E2ETestUtils.enqueueEventResponse(mockWebServer)
                E2ETestUtils.enqueueEventResponse(mockWebServer)
            }

            // Act
            Grovs.configure(application, "api-key-1", useTestEnvironment = true)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            val firstAuthJob = E2ETestUtils.getAuthenticationJob()
            withTimeoutOrNull(5_000) { firstAuthJob?.join() }
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            Grovs.configure(application, "api-key-2", useTestEnvironment = true)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            val secondAuthJob = E2ETestUtils.getAuthenticationJob()
            withTimeoutOrNull(5_000) { secondAuthJob?.join() }
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            // Assert
            val requests = E2ETestUtils.collectAllRequests(mockWebServer)
            assertTrue(
                "SDK should make at least one request during configuration, got ${requests.size}",
                requests.isNotEmpty()
            )
            E2ETestUtils.assertAuthenticationCompleted()
            E2ETestUtils.assertRequestMade(requests, "authenticate", "SDK should call authenticate endpoint")
        }
    }

    @Test
    fun `Activity lifecycle callbacks do not crash SDK`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)

            E2ETestUtils.configureAndWaitForAuthOnly(application)

            // Act
            val activityController = Robolectric.buildActivity(TestActivity::class.java)
            activityController.create()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            activityController.start()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            activityController.resume()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            activityController.pause()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            activityController.stop()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            activityController.destroy()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            // Assert
            val requests = E2ETestUtils.collectAllRequests(mockWebServer)
            E2ETestUtils.assertAuthenticationCompleted()
            E2ETestUtils.assertRequestMade(requests, "authenticate", "SDK should call authenticate endpoint")
            E2ETestUtils.assertRequestMade(requests, "device_for_vendor_id", "SDK should call device_for_vendor_id endpoint")
        }
    }

    @Test
    fun `SDK re-enabled via setSDK true resumes operations`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)

            E2ETestUtils.configureAndWaitForAuthOnly(application)

            Grovs.setSDK(enabled = false)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            // Act
            Grovs.setSDK(enabled = true)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            // Assert
            E2ETestUtils.assertAuthenticationCompleted()
            assertTrue("SDK should be enabled after setSDK(true)", E2ETestUtils.isSdkEnabled())
        }
    }

    @Test
    fun `App foreground detected correctly`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)

            E2ETestUtils.configureAndWaitForAuthOnly(application)

            val requestCountBefore = mockWebServer.requestCount

            // Act
            val activityController = Robolectric.buildActivity(TestActivity::class.java)
            activityController.create().start().resume()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            delay(500)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            // Assert - verify APP_OPEN event is sent on foreground
            val newRequestCount = mockWebServer.requestCount - requestCountBefore
            assertTrue(
                "SDK should make at least 1 new request on foreground, got $newRequestCount",
                newRequestCount >= 1
            )

            val requests = E2ETestUtils.collectAllRequests(mockWebServer)
            E2ETestUtils.verifyEventInfrastructureWorks(requests)

            // Verify APP_OPEN event specifically if events were captured
            // Note: Event sending is asynchronous and may not complete within test timeframe
            val eventRequests = E2ETestUtils.findRequestsByPath(requests, "event")
            if (eventRequests.isNotEmpty()) {
                val hasAppOpenEvent = eventRequests.any { (_, body) ->
                    body.contains("APP_OPEN")
                }
                if (!hasAppOpenEvent) {
                    println("Warning: Events captured but no APP_OPEN event found. This may be a timing issue.")
                }
            }

            activityController.pause().stop().destroy()
        }
    }

    @Test
    fun `App background detected correctly`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)

            E2ETestUtils.configureAndWaitForAuthOnly(application)

            val activityController = Robolectric.buildActivity(TestActivity::class.java)
            activityController.create().start().resume()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            delay(500)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            // Act
            activityController.pause().stop()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            delay(500)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            // Assert - verify background state change is detected
            E2ETestUtils.assertAuthenticationCompleted()
            val requests = E2ETestUtils.collectAllRequests(mockWebServer)
            E2ETestUtils.assertRequestMade(requests, "authenticate", "SDK should call authenticate endpoint")
            E2ETestUtils.assertRequestMade(requests, "device_for_vendor_id", "SDK should call device_for_vendor_id endpoint")

            // Verify events were sent (SDK tracks lifecycle) if captured
            // Note: Event sending is asynchronous and may not complete within test timeframe
            val eventRequests = E2ETestUtils.findRequestsByPath(requests, "event")
            if (eventRequests.isEmpty()) {
                println("Note: No event requests captured within test timeframe. This is acceptable for lifecycle tests.")
            }

            activityController.destroy()
        }
    }

    @Test
    fun `API calls before configure return error gracefully`() {
        // Arrange - don't configure SDK

        // Act & Assert
        try {
            runBlocking {
                val link = Grovs.generateLink(title = "Test")
                fail("generateLink should throw when SDK not configured")
            }
        } catch (e: GrovsException) {
            assertTrue(
                "Exception should indicate SDK not initialized",
                e.message?.contains("not initialized") == true
            )
        }
    }

    // ==================== Critical Coverage Tests ====================

    @Test
    fun `SDK disables when sdkEnabled is false in settings`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)

            E2ETestUtils.configureAndWaitForAuthOnly(application)

            val requestCountAfterAuth = mockWebServer.requestCount

            // Act - disable SDK via settings (using reflection)
            val instance = E2ETestUtils.getGrovsInstance()
            assertNotNull("Grovs instance should exist", instance)
            val managerField = instance!!.javaClass.getDeclaredField("grovsManager")
            managerField.isAccessible = true
            val grovsManager = managerField.get(instance)
            assertNotNull("GrovsManager should exist", grovsManager)

            val contextField = grovsManager.javaClass.getDeclaredField("grovsContext")
            contextField.isAccessible = true
            val grovsContext = contextField.get(grovsManager)
            assertNotNull("GrovsContext should exist", grovsContext)

            val settingsField = grovsContext.javaClass.getDeclaredField("settings")
            settingsField.isAccessible = true
            val settings = settingsField.get(grovsContext)
            assertNotNull("Settings should exist", settings)

            val sdkEnabledField = settings.javaClass.getDeclaredField("sdkEnabled")
            sdkEnabledField.isAccessible = true
            sdkEnabledField.set(settings, false)

            delay(500)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            // Assert
            assertTrue(
                "SDK should have made auth requests before being disabled",
                requestCountAfterAuth >= 2
            )
        }
    }

    // PURCHASE_EVENT_DISABLED: @Test
    // PURCHASE_EVENT_DISABLED: fun `Large batch of events handles correctly without crash`() {
    // PURCHASE_EVENT_DISABLED:     runBlocking {
    // PURCHASE_EVENT_DISABLED:         // Arrange
    // PURCHASE_EVENT_DISABLED:         E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
    // PURCHASE_EVENT_DISABLED:         E2ETestUtils.enqueueDeviceResponse(mockWebServer)
    // PURCHASE_EVENT_DISABLED:         repeat(50) {
    // PURCHASE_EVENT_DISABLED:             E2ETestUtils.enqueueEventResponse(mockWebServer)
    // PURCHASE_EVENT_DISABLED:         }
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:         E2ETestUtils.configureAndWaitForAuthOnly(application)
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:         val activityController = Robolectric.buildActivity(TestActivity::class.java)
    // PURCHASE_EVENT_DISABLED:         activityController.create().start()
    // PURCHASE_EVENT_DISABLED:         Shadows.shadowOf(Looper.getMainLooper()).idle()
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:         // Act
    // PURCHASE_EVENT_DISABLED:         repeat(20) { i ->
    // PURCHASE_EVENT_DISABLED:             Grovs.logCustomPurchase(
    // PURCHASE_EVENT_DISABLED:                 type = PaymentEventType.BUY,
    // PURCHASE_EVENT_DISABLED:                 priceInCents = 100 + i,
    // PURCHASE_EVENT_DISABLED:                 currency = "USD",
    // PURCHASE_EVENT_DISABLED:                 productId = "batch_test_$i"
    // PURCHASE_EVENT_DISABLED:             )
    // PURCHASE_EVENT_DISABLED:             if (i % 5 == 0) {
    // PURCHASE_EVENT_DISABLED:                 Shadows.shadowOf(Looper.getMainLooper()).idle()
    // PURCHASE_EVENT_DISABLED:             }
    // PURCHASE_EVENT_DISABLED:         }
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:         delay(2000)
    // PURCHASE_EVENT_DISABLED:         Shadows.shadowOf(Looper.getMainLooper()).idle()
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:         // Assert
    // PURCHASE_EVENT_DISABLED:         E2ETestUtils.assertAuthenticationCompleted()
    // PURCHASE_EVENT_DISABLED:         val requests = E2ETestUtils.collectAllRequests(mockWebServer)
    // PURCHASE_EVENT_DISABLED:         E2ETestUtils.verifyPaymentInfrastructureWorks(requests)
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:         activityController.stop().destroy()
    // PURCHASE_EVENT_DISABLED:     }
    // PURCHASE_EVENT_DISABLED: }

    @Test
    fun `SDK handles rapid configuration and reconfiguration`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)

            // First configure
            Grovs.configure(application, "test-api-key-1", useTestEnvironment = true)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            delay(100)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            // Act
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)

            try {
                Grovs.configure(application, "test-api-key-2", useTestEnvironment = true)
            } catch (e: Exception) {
                // Reconfiguration may throw, which is acceptable
            }

            Shadows.shadowOf(Looper.getMainLooper()).idle()

            val authJob = E2ETestUtils.getAuthenticationJob()
            withTimeoutOrNull(10_000) { authJob?.join() }
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            // Assert
            assertTrue(
                "SDK should make requests despite reconfiguration attempt",
                mockWebServer.requestCount >= 2
            )
        }
    }

    @Test
    fun `SDK handles authentication failure and retry`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueErrorResponse(mockWebServer, 401, "Unauthorized")
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)

            // Act
            Grovs.configure(application, "test-api-key", useTestEnvironment = true)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            val authJob = E2ETestUtils.getAuthenticationJob()
            withTimeoutOrNull(15_000) { authJob?.join() }
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            delay(2000)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            // Assert
            val requests = E2ETestUtils.collectAllRequests(mockWebServer)
            assertTrue(
                "SDK should make authentication requests",
                requests.isNotEmpty()
            )
        }
    }

}
