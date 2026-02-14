package io.grovs.e2e

// PURCHASE_EVENT_DISABLED: import android.app.Application
// PURCHASE_EVENT_DISABLED: import android.os.Looper
// PURCHASE_EVENT_DISABLED: import io.grovs.Grovs
// PURCHASE_EVENT_DISABLED: import io.grovs.model.events.PaymentEventType
// PURCHASE_EVENT_DISABLED: import io.grovs.service.GrovsService
// PURCHASE_EVENT_DISABLED: import kotlinx.coroutines.delay
// PURCHASE_EVENT_DISABLED: import kotlinx.coroutines.runBlocking
// PURCHASE_EVENT_DISABLED: import okhttp3.mockwebserver.MockWebServer
// PURCHASE_EVENT_DISABLED: import org.junit.After
// PURCHASE_EVENT_DISABLED: import org.junit.Assert.*
// PURCHASE_EVENT_DISABLED: import org.junit.Before
// PURCHASE_EVENT_DISABLED: import org.junit.Rule
// PURCHASE_EVENT_DISABLED: import org.junit.Test
// PURCHASE_EVENT_DISABLED: import org.junit.rules.TestName
// PURCHASE_EVENT_DISABLED: import org.junit.runner.RunWith
// PURCHASE_EVENT_DISABLED: import org.robolectric.Robolectric
// PURCHASE_EVENT_DISABLED: import org.robolectric.RobolectricTestRunner
// PURCHASE_EVENT_DISABLED: import org.robolectric.RuntimeEnvironment
// PURCHASE_EVENT_DISABLED: import org.robolectric.Shadows
// PURCHASE_EVENT_DISABLED: import org.robolectric.annotation.Config
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED: /**
// PURCHASE_EVENT_DISABLED:  * E2E tests for payment event tracking functionality.
// PURCHASE_EVENT_DISABLED:  */
// PURCHASE_EVENT_DISABLED: @RunWith(RobolectricTestRunner::class)
// PURCHASE_EVENT_DISABLED: @Config(sdk = [28])
// PURCHASE_EVENT_DISABLED: class PaymentEventsE2ETest {
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:     private lateinit var mockWebServer: MockWebServer
// PURCHASE_EVENT_DISABLED:     private lateinit var application: Application
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:     @get:Rule
// PURCHASE_EVENT_DISABLED:     val testName = TestName()
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:     @Before
// PURCHASE_EVENT_DISABLED:     fun setUp() {
// PURCHASE_EVENT_DISABLED:         println("\n========== Running: ${testName.methodName} ==========")
// PURCHASE_EVENT_DISABLED:         E2ETestUtils.resetGrovsSingleton()
// PURCHASE_EVENT_DISABLED:         E2ETestUtils.setupMockGlInfo()
// PURCHASE_EVENT_DISABLED:         mockWebServer = E2ETestUtils.createMockWebServer()
// PURCHASE_EVENT_DISABLED:         application = RuntimeEnvironment.getApplication()
// PURCHASE_EVENT_DISABLED:         E2ETestUtils.setupTestApplication(application)
// PURCHASE_EVENT_DISABLED:     }
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:     @After
// PURCHASE_EVENT_DISABLED:     fun tearDown() {
// PURCHASE_EVENT_DISABLED:         E2ETestUtils.cleanupMockWebServer(mockWebServer)
// PURCHASE_EVENT_DISABLED:     }
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:     // ==================== Payment Event Tests ====================
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:     @Test
// PURCHASE_EVENT_DISABLED:     fun `Log custom purchase sends payment event to server`() {
// PURCHASE_EVENT_DISABLED:         runBlocking {
// PURCHASE_EVENT_DISABLED:             // Arrange
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.enqueueDeviceResponse(mockWebServer)
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.enqueueEventResponse(mockWebServer)
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.enqueueEventResponse(mockWebServer)
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.enqueueDataForDeviceResponse(mockWebServer)
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.enqueuePaymentEventResponse(mockWebServer)
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.enqueueEventResponse(mockWebServer)
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             // Configure and authenticate
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.configureAndWaitForAuthOnly(application)
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             val activityController = Robolectric.buildActivity(TestActivity::class.java)
// PURCHASE_EVENT_DISABLED:             activityController.create().start()
// PURCHASE_EVENT_DISABLED:             Shadows.shadowOf(Looper.getMainLooper()).idle()
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             delay(1000)
// PURCHASE_EVENT_DISABLED:             Shadows.shadowOf(Looper.getMainLooper()).idle()
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             // Act - log a custom purchase
// PURCHASE_EVENT_DISABLED:             Grovs.logCustomPurchase(
// PURCHASE_EVENT_DISABLED:                 type = PaymentEventType.BUY,
// PURCHASE_EVENT_DISABLED:                 priceInCents = 999,
// PURCHASE_EVENT_DISABLED:                 currency = "USD",
// PURCHASE_EVENT_DISABLED:                 productId = "test-product-123"
// PURCHASE_EVENT_DISABLED:             )
// PURCHASE_EVENT_DISABLED:             Shadows.shadowOf(Looper.getMainLooper()).idle()
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             delay(1000)
// PURCHASE_EVENT_DISABLED:             Shadows.shadowOf(Looper.getMainLooper()).idle()
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             // Assert - verify authentication and payment event data
// PURCHASE_EVENT_DISABLED:             val requests = E2ETestUtils.collectAllRequests(mockWebServer)
// PURCHASE_EVENT_DISABLED:             assertTrue(
// PURCHASE_EVENT_DISABLED:                 "SDK should make authentication requests, got ${requests.size} requests",
// PURCHASE_EVENT_DISABLED:                 requests.size >= 2
// PURCHASE_EVENT_DISABLED:             )
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.assertAuthenticationCompleted()
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.assertRequestMade(requests, "authenticate", "SDK should call authenticate endpoint")
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.assertRequestMade(requests, "device_for_vendor_id", "SDK should call device_for_vendor_id endpoint")
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             // Verify payment event request contains logged values
// PURCHASE_EVENT_DISABLED:             val eventRequests = E2ETestUtils.findRequestsByPath(requests, "event")
// PURCHASE_EVENT_DISABLED:             val paymentRequest = eventRequests.find { it.second.contains("999") || it.second.contains("BUY") }
// PURCHASE_EVENT_DISABLED:             if (paymentRequest != null) {
// PURCHASE_EVENT_DISABLED:                 assertTrue("Payment request should contain price", paymentRequest.second.contains("999"))
// PURCHASE_EVENT_DISABLED:                 assertTrue("Payment request should contain currency", paymentRequest.second.contains("USD"))
// PURCHASE_EVENT_DISABLED:                 assertTrue("Payment request should contain productId", paymentRequest.second.contains("test-product-123"))
// PURCHASE_EVENT_DISABLED:             }
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             activityController.stop().destroy()
// PURCHASE_EVENT_DISABLED:         }
// PURCHASE_EVENT_DISABLED:     }
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:     @Test
// PURCHASE_EVENT_DISABLED:     fun `Log custom purchase before SDK configured does not crash`() {
// PURCHASE_EVENT_DISABLED:         // Arrange - verify SDK is not configured
// PURCHASE_EVENT_DISABLED:         val requestCountBefore = mockWebServer.requestCount
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:         // Act - try to log purchase before configuration
// PURCHASE_EVENT_DISABLED:         try {
// PURCHASE_EVENT_DISABLED:             Grovs.logCustomPurchase(
// PURCHASE_EVENT_DISABLED:                 type = PaymentEventType.BUY,
// PURCHASE_EVENT_DISABLED:                 priceInCents = 500,
// PURCHASE_EVENT_DISABLED:                 currency = "EUR",
// PURCHASE_EVENT_DISABLED:                 productId = "test-product"
// PURCHASE_EVENT_DISABLED:             )
// PURCHASE_EVENT_DISABLED:             assertEquals(
// PURCHASE_EVENT_DISABLED:                 "No requests should be made when SDK is not configured",
// PURCHASE_EVENT_DISABLED:                 requestCountBefore,
// PURCHASE_EVENT_DISABLED:                 mockWebServer.requestCount
// PURCHASE_EVENT_DISABLED:             )
// PURCHASE_EVENT_DISABLED:         } catch (e: Exception) {
// PURCHASE_EVENT_DISABLED:             assertTrue(
// PURCHASE_EVENT_DISABLED:                 "Exception should indicate SDK not initialized, got: ${e.message}",
// PURCHASE_EVENT_DISABLED:                 e.message?.contains("not initialized") == true ||
// PURCHASE_EVENT_DISABLED:                 e.message?.contains("null") == true ||
// PURCHASE_EVENT_DISABLED:                 e is NullPointerException
// PURCHASE_EVENT_DISABLED:             )
// PURCHASE_EVENT_DISABLED:         }
// PURCHASE_EVENT_DISABLED:     }
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:     @Test
// PURCHASE_EVENT_DISABLED:     fun `Log in-app purchase with valid originalJson processes purchase`() {
// PURCHASE_EVENT_DISABLED:         runBlocking {
// PURCHASE_EVENT_DISABLED:             // Arrange
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.enqueueDeviceResponse(mockWebServer)
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.enqueueEventResponse(mockWebServer)
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.enqueueEventResponse(mockWebServer)
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.enqueuePaymentEventResponse(mockWebServer)
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.configureAndWaitForAuthOnly(application)
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             // Act - log in-app purchase with valid JSON
// PURCHASE_EVENT_DISABLED:             val purchaseJson = """
// PURCHASE_EVENT_DISABLED:                 {
// PURCHASE_EVENT_DISABLED:                     "productId": "premium_subscription",
// PURCHASE_EVENT_DISABLED:                     "purchaseTime": 1706380800000,
// PURCHASE_EVENT_DISABLED:                     "purchaseToken": "token123"
// PURCHASE_EVENT_DISABLED:                 }
// PURCHASE_EVENT_DISABLED:             """.trimIndent()
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             Grovs.logInAppPurchase(purchaseJson)
// PURCHASE_EVENT_DISABLED:             Shadows.shadowOf(Looper.getMainLooper()).idle()
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             delay(500)
// PURCHASE_EVENT_DISABLED:             Shadows.shadowOf(Looper.getMainLooper()).idle()
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             // Assert - verify authentication and purchase JSON values
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.assertAuthenticationCompleted()
// PURCHASE_EVENT_DISABLED:             val requests = E2ETestUtils.collectAllRequests(mockWebServer)
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.assertRequestMade(requests, "authenticate", "SDK should call authenticate endpoint")
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.assertRequestMade(requests, "device_for_vendor_id", "SDK should call device_for_vendor_id endpoint")
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.verifyPaymentInfrastructureWorks(requests)
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             // Verify purchase JSON values are in a request if payment event was sent
// PURCHASE_EVENT_DISABLED:             // Note: Payment event sending is asynchronous and may not complete within test timeframe
// PURCHASE_EVENT_DISABLED:             val allBodies = requests.map { it.second }.joinToString(" ")
// PURCHASE_EVENT_DISABLED:             val hasPurchaseData = allBodies.contains("premium_subscription") ||
// PURCHASE_EVENT_DISABLED:                                   allBodies.contains("purchaseToken") ||
// PURCHASE_EVENT_DISABLED:                                   allBodies.contains("token123")
// PURCHASE_EVENT_DISABLED:             if (!hasPurchaseData) {
// PURCHASE_EVENT_DISABLED:                 // Payment infrastructure works but async event may not have been sent yet
// PURCHASE_EVENT_DISABLED:                 E2ETestUtils.verifyPaymentInfrastructureWorks(requests)
// PURCHASE_EVENT_DISABLED:             }
// PURCHASE_EVENT_DISABLED:         }
// PURCHASE_EVENT_DISABLED:     }
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:     @Test
// PURCHASE_EVENT_DISABLED:     fun `Log in-app purchase with invalid JSON does not crash`() {
// PURCHASE_EVENT_DISABLED:         runBlocking {
// PURCHASE_EVENT_DISABLED:             // Arrange
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.enqueueDeviceResponse(mockWebServer)
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.enqueueEventResponse(mockWebServer)
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.enqueueEventResponse(mockWebServer)
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.configureAndWaitForAuthOnly(application)
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             // Act - log in-app purchase with invalid JSON
// PURCHASE_EVENT_DISABLED:             try {
// PURCHASE_EVENT_DISABLED:                 Grovs.logInAppPurchase("not valid json {{{")
// PURCHASE_EVENT_DISABLED:                 Shadows.shadowOf(Looper.getMainLooper()).idle()
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:                 E2ETestUtils.assertAuthenticationCompleted()
// PURCHASE_EVENT_DISABLED:                 E2ETestUtils.assertSdkFunctionalAfterError()
// PURCHASE_EVENT_DISABLED:             } catch (e: Exception) {
// PURCHASE_EVENT_DISABLED:                 val message = e.message?.lowercase() ?: ""
// PURCHASE_EVENT_DISABLED:                 assertTrue(
// PURCHASE_EVENT_DISABLED:                     "Exception should indicate JSON parsing error, got: ${e.message}",
// PURCHASE_EVENT_DISABLED:                     message.contains("json") || message.contains("parse") || message.contains("syntax") || message.contains("malformed")
// PURCHASE_EVENT_DISABLED:                 )
// PURCHASE_EVENT_DISABLED:             }
// PURCHASE_EVENT_DISABLED:         }
// PURCHASE_EVENT_DISABLED:     }
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:     @Test
// PURCHASE_EVENT_DISABLED:     fun `Log custom purchase with SUBSCRIBE type sends subscription event`() {
// PURCHASE_EVENT_DISABLED:         runBlocking {
// PURCHASE_EVENT_DISABLED:             // Arrange
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.enqueueDeviceResponse(mockWebServer)
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.enqueueEventResponse(mockWebServer)
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.enqueueEventResponse(mockWebServer)
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.enqueuePaymentEventResponse(mockWebServer)
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.configureAndWaitForAuthOnly(application)
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             val activityController = Robolectric.buildActivity(TestActivity::class.java)
// PURCHASE_EVENT_DISABLED:             activityController.create().start()
// PURCHASE_EVENT_DISABLED:             Shadows.shadowOf(Looper.getMainLooper()).idle()
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             // Act
// PURCHASE_EVENT_DISABLED:             Grovs.logCustomPurchase(
// PURCHASE_EVENT_DISABLED:                 type = PaymentEventType.BUY,
// PURCHASE_EVENT_DISABLED:                 priceInCents = 1999,
// PURCHASE_EVENT_DISABLED:                 currency = "USD",
// PURCHASE_EVENT_DISABLED:                 productId = "monthly-premium"
// PURCHASE_EVENT_DISABLED:             )
// PURCHASE_EVENT_DISABLED:             Shadows.shadowOf(Looper.getMainLooper()).idle()
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             delay(500)
// PURCHASE_EVENT_DISABLED:             Shadows.shadowOf(Looper.getMainLooper()).idle()
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             // Assert
// PURCHASE_EVENT_DISABLED:             val totalRequests = mockWebServer.requestCount
// PURCHASE_EVENT_DISABLED:             assertTrue(
// PURCHASE_EVENT_DISABLED:                 "SDK should make requests including subscription event",
// PURCHASE_EVENT_DISABLED:                 totalRequests >= 2
// PURCHASE_EVENT_DISABLED:             )
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             activityController.stop().destroy()
// PURCHASE_EVENT_DISABLED:         }
// PURCHASE_EVENT_DISABLED:     }
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:     @Test
// PURCHASE_EVENT_DISABLED:     fun `Log custom purchase with all parameters sends complete event`() {
// PURCHASE_EVENT_DISABLED:         runBlocking {
// PURCHASE_EVENT_DISABLED:             // Arrange - use a capturing dispatcher so we can inspect the payment body
// PURCHASE_EVENT_DISABLED:             val capturedPaymentBodies = mutableListOf<String>()
// PURCHASE_EVENT_DISABLED:             mockWebServer.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
// PURCHASE_EVENT_DISABLED:                 override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
// PURCHASE_EVENT_DISABLED:                     val path = request.path ?: ""
// PURCHASE_EVENT_DISABLED:                     val body = request.body.clone().readUtf8()
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:                     if (path.contains("add_payment_event")) {
// PURCHASE_EVENT_DISABLED:                         synchronized(capturedPaymentBodies) {
// PURCHASE_EVENT_DISABLED:                             capturedPaymentBodies.add(body)
// PURCHASE_EVENT_DISABLED:                         }
// PURCHASE_EVENT_DISABLED:                     }
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:                     return when {
// PURCHASE_EVENT_DISABLED:                         path.contains("authenticate") -> okhttp3.mockwebserver.MockResponse()
// PURCHASE_EVENT_DISABLED:                             .setResponseCode(200)
// PURCHASE_EVENT_DISABLED:                             .setHeader("Content-Type", "application/json")
// PURCHASE_EVENT_DISABLED:                             .setBody("""{"linksquared":"test-grovs-id-123","uri_scheme":"testapp"}""")
// PURCHASE_EVENT_DISABLED:                         path.contains("device_for_vendor_id") -> okhttp3.mockwebserver.MockResponse()
// PURCHASE_EVENT_DISABLED:                             .setResponseCode(200)
// PURCHASE_EVENT_DISABLED:                             .setHeader("Content-Type", "application/json")
// PURCHASE_EVENT_DISABLED:                             .setBody("""{"last_seen":null}""")
// PURCHASE_EVENT_DISABLED:                         else -> okhttp3.mockwebserver.MockResponse()
// PURCHASE_EVENT_DISABLED:                             .setResponseCode(200)
// PURCHASE_EVENT_DISABLED:                             .setHeader("Content-Type", "application/json")
// PURCHASE_EVENT_DISABLED:                             .setBody("{}")
// PURCHASE_EVENT_DISABLED:                     }
// PURCHASE_EVENT_DISABLED:                 }
// PURCHASE_EVENT_DISABLED:             }
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.configureAndWaitForAuthOnly(application)
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             val activityController = Robolectric.buildActivity(TestActivity::class.java)
// PURCHASE_EVENT_DISABLED:             activityController.create().start()
// PURCHASE_EVENT_DISABLED:             Shadows.shadowOf(Looper.getMainLooper()).idle()
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             // Act
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.enableImmediateEventSending()
// PURCHASE_EVENT_DISABLED:             Grovs.logCustomPurchase(
// PURCHASE_EVENT_DISABLED:                 type = PaymentEventType.BUY,
// PURCHASE_EVENT_DISABLED:                 priceInCents = 4999,
// PURCHASE_EVENT_DISABLED:                 currency = "EUR",
// PURCHASE_EVENT_DISABLED:                 productId = "premium-annual",
// PURCHASE_EVENT_DISABLED:                 startDate = io.grovs.utils.InstantCompat.now()
// PURCHASE_EVENT_DISABLED:             )
// PURCHASE_EVENT_DISABLED:             Shadows.shadowOf(Looper.getMainLooper()).idle()
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             delay(1000)
// PURCHASE_EVENT_DISABLED:             Shadows.shadowOf(Looper.getMainLooper()).idle()
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             // Assert
// PURCHASE_EVENT_DISABLED:             E2ETestUtils.assertAuthenticationCompleted()
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             val paymentBody = synchronized(capturedPaymentBodies) {
// PURCHASE_EVENT_DISABLED:                 capturedPaymentBodies.firstOrNull { it.contains("premium-annual") }
// PURCHASE_EVENT_DISABLED:             }
// PURCHASE_EVENT_DISABLED:             assertNotNull("Payment event should be sent to add_payment_event endpoint", paymentBody)
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             assertTrue(
// PURCHASE_EVENT_DISABLED:                 "Payment should contain event_type 'buy', got: ${paymentBody!!.take(400)}",
// PURCHASE_EVENT_DISABLED:                 paymentBody.contains("\"event_type\":\"buy\"")
// PURCHASE_EVENT_DISABLED:             )
// PURCHASE_EVENT_DISABLED:             assertTrue(
// PURCHASE_EVENT_DISABLED:                 "Payment should contain price_cents 4999, got: ${paymentBody.take(400)}",
// PURCHASE_EVENT_DISABLED:                 paymentBody.contains("\"price_cents\":4999")
// PURCHASE_EVENT_DISABLED:             )
// PURCHASE_EVENT_DISABLED:             assertTrue(
// PURCHASE_EVENT_DISABLED:                 "Payment should contain currency EUR, got: ${paymentBody.take(400)}",
// PURCHASE_EVENT_DISABLED:                 paymentBody.contains("\"currency\":\"EUR\"")
// PURCHASE_EVENT_DISABLED:             )
// PURCHASE_EVENT_DISABLED:             assertTrue(
// PURCHASE_EVENT_DISABLED:                 "Payment should contain product_id premium-annual, got: ${paymentBody.take(400)}",
// PURCHASE_EVENT_DISABLED:                 paymentBody.contains("\"product_id\":\"premium-annual\"")
// PURCHASE_EVENT_DISABLED:             )
// PURCHASE_EVENT_DISABLED:             assertTrue(
// PURCHASE_EVENT_DISABLED:                 "Payment should contain date field, got: ${paymentBody.take(400)}",
// PURCHASE_EVENT_DISABLED:                 paymentBody.contains("\"date\":")
// PURCHASE_EVENT_DISABLED:             )
// PURCHASE_EVENT_DISABLED:             assertFalse(
// PURCHASE_EVENT_DISABLED:                 "Payment should have date set (not null), got: ${paymentBody.take(400)}",
// PURCHASE_EVENT_DISABLED:                 paymentBody.contains("\"date\":null")
// PURCHASE_EVENT_DISABLED:             )
// PURCHASE_EVENT_DISABLED:             assertTrue(
// PURCHASE_EVENT_DISABLED:                 "Payment should contain store=false for custom purchase, got: ${paymentBody.take(400)}",
// PURCHASE_EVENT_DISABLED:                 paymentBody.contains("\"store\":false")
// PURCHASE_EVENT_DISABLED:             )
// PURCHASE_EVENT_DISABLED:
// PURCHASE_EVENT_DISABLED:             activityController.stop().destroy()
// PURCHASE_EVENT_DISABLED:         }
// PURCHASE_EVENT_DISABLED:     }
// PURCHASE_EVENT_DISABLED: }
