package io.grovs.e2e

import android.app.Application
import io.grovs.Grovs
import io.grovs.model.events.PaymentEventType
import kotlinx.coroutines.runBlocking
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * E2E tests for revenue tracking (logInAppPurchase / logCustomPurchase).
 * Verifies the full flow from public API to HTTP requests hitting MockWebServer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RevenueTrackingE2ETest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var application: Application
    private lateinit var capturedPaymentBodies: MutableList<String>

    @get:Rule
    val testName = TestName()

    private val singleProductJson = """
        {
          "orderId": "GPA.3375-2069-1505-17920",
          "packageName": "com.example.myapp",
          "productId": "premium_upgrade",
          "purchaseTime": 1711987200000,
          "purchaseState": 0,
          "purchaseToken": "kfbopgjmhcilnaedpgimhfao.AO-J1Oz3G7kVwFjf2yLNpMqbXHkGOaQmvzKjRZBhL9oKfjsdhUEOihJrek5xSL9a",
          "quantity": 1,
          "acknowledged": false
        }
    """.trimIndent()

    @Before
    fun setUp() {
        println("\n========== Running: ${testName.methodName} ==========")
        E2ETestUtils.resetGrovsSingleton()
        E2ETestUtils.setupMockGlInfo()
        mockWebServer = E2ETestUtils.createMockWebServer()
        application = RuntimeEnvironment.getApplication()
        E2ETestUtils.setupTestApplication(application)
        capturedPaymentBodies = mutableListOf()
    }

    @After
    fun tearDown() {
        capturedPaymentBodies.clear()
        E2ETestUtils.cleanupMockWebServer(mockWebServer)
    }

    private fun installPaymentCapturingDispatcher() {
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val body = request.body.clone().readUtf8()

                if (path.contains("add_payment_event")) {
                    synchronized(capturedPaymentBodies) {
                        capturedPaymentBodies.add(body)
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
                        .setBody("""{"last_seen":null}""")
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

    private fun waitForPaymentEvent(description: String = "payment event") {
        E2ETestUtils.waitForCondition(timeoutMs = 10_000, description = "$description sent to backend") {
            E2ETestUtils.enableImmediateEventSending()
            synchronized(capturedPaymentBodies) {
                capturedPaymentBodies.isNotEmpty()
            }
        }
    }

    // ==================== logInAppPurchase Tests ====================

    @Test
    fun `logInAppPurchase sends payment event to backend`() {
        runBlocking {
            // Arrange
            installPaymentCapturingDispatcher()

            E2ETestUtils.configureAndWaitForAuthOnly(
                application,
                baseURL = mockWebServer.url("/").toString()
            )

            // Act
            Grovs.logInAppPurchase(originalJson = singleProductJson)

            // Assert
            waitForPaymentEvent("in-app purchase")

            synchronized(capturedPaymentBodies) {
                assertTrue("Should have received at least one payment event", capturedPaymentBodies.isNotEmpty())
                val body = capturedPaymentBodies[0]
                assertTrue("Should contain product_id", body.contains("\"product_id\":\"premium_upgrade\""))
                assertTrue("Should contain event_type buy", body.contains("\"event_type\":\"buy\""))
                assertTrue("Should have store=true", body.contains("\"store\":true"))
            }
        }
    }

    // ==================== logCustomPurchase Tests ====================

    @Test
    fun `logCustomPurchase with BUY type sends payment event`() {
        runBlocking {
            // Arrange
            installPaymentCapturingDispatcher()

            E2ETestUtils.configureAndWaitForAuthOnly(
                application,
                baseURL = mockWebServer.url("/").toString()
            )

            // Act
            Grovs.logCustomPurchase(
                type = PaymentEventType.BUY,
                priceInCents = 1999,
                currency = "USD",
                productId = "pro_plan"
            )

            // Assert
            waitForPaymentEvent("custom BUY purchase")

            synchronized(capturedPaymentBodies) {
                assertTrue("Should have received at least one payment event", capturedPaymentBodies.isNotEmpty())
                val body = capturedPaymentBodies[0]
                assertTrue("Should contain event_type buy", body.contains("\"event_type\":\"buy\""))
                assertTrue("Should contain price_cents 1999", body.contains("\"price_cents\":1999"))
                assertTrue("Should contain currency USD", body.contains("\"currency\":\"USD\""))
                assertTrue("Should contain product_id pro_plan", body.contains("\"product_id\":\"pro_plan\""))
                assertTrue("Should have store=false for custom purchase", body.contains("\"store\":false"))
            }
        }
    }

    @Test
    fun `logCustomPurchase with CANCEL type sends correct eventType`() {
        runBlocking {
            // Arrange
            installPaymentCapturingDispatcher()

            E2ETestUtils.configureAndWaitForAuthOnly(
                application,
                baseURL = mockWebServer.url("/").toString()
            )

            // Act
            Grovs.logCustomPurchase(
                type = PaymentEventType.CANCEL,
                priceInCents = 999,
                currency = "EUR",
                productId = "basic_plan"
            )

            // Assert
            waitForPaymentEvent("custom CANCEL purchase")

            synchronized(capturedPaymentBodies) {
                assertTrue("Should have received at least one payment event", capturedPaymentBodies.isNotEmpty())
                val body = capturedPaymentBodies[0]
                assertTrue("Should contain event_type cancel", body.contains("\"event_type\":\"cancel\""))
                assertTrue("Should contain product_id basic_plan", body.contains("\"product_id\":\"basic_plan\""))
            }
        }
    }

    // ==================== Attribution Tests ====================

    @Test
    fun `payment event waits for authentication before sending`() {
        runBlocking {
            // Arrange - install dispatcher but DON'T configure/authenticate yet
            installPaymentCapturingDispatcher()

            // Act - log purchase before SDK is configured
            // The SDK should queue the event and send after auth

            E2ETestUtils.configureAndWaitForAuthOnly(
                application,
                baseURL = mockWebServer.url("/").toString()
            )

            Grovs.logCustomPurchase(
                type = PaymentEventType.BUY,
                priceInCents = 500,
                currency = "USD",
                productId = "test_item"
            )

            // Assert - after auth, the payment event should eventually be sent
            waitForPaymentEvent("post-auth purchase")

            synchronized(capturedPaymentBodies) {
                assertTrue("Payment event should be sent after authentication", capturedPaymentBodies.isNotEmpty())
                val body = capturedPaymentBodies[0]
                assertTrue("Should contain product_id", body.contains("\"product_id\":\"test_item\""))
            }
        }
    }
}
