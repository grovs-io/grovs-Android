package io.grovs

import io.grovs.TestAssertions.assertEqualsWithContext
import io.grovs.TestAssertions.assertNotNullWithContext
import io.grovs.TestAssertions.assertTrueWithContext
import io.grovs.TestAssertions.assertFalseWithContext
import io.grovs.model.AuthenticationResponse
import io.grovs.model.DeeplinkDetails
import io.grovs.model.GenerateLinkResponse
import io.grovs.model.GetDeviceResponse
// PURCHASE_EVENT_DISABLED: import io.grovs.model.events.PaymentEventType
import io.grovs.utils.InstantCompat
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Response

/**
 * Core integration tests for the Grovs SDK API layer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GrovsSDKIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApi: MockGrovsApi

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockApi = MockGrovsApi()
        mockkObject(io.grovs.model.DebugLogger.Companion)
        every { io.grovs.model.DebugLogger.instance } returns mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
        mockApi.reset()
    }

    // ==================== Authentication Tests ====================

    @Test
    fun `MockGrovsApi authenticate returns grovsId and uriScheme on successful response`() = runTest {
        val expectedGrovsId = "grovs_test_123"
        mockApi.authenticateResponse = Response.success(
            AuthenticationResponse(
                grovsId = expectedGrovsId,
                uriScheme = "testapp",
                sdkIdentifier = null,
                sdkAttributes = null
            )
        )

        val appDetails = createTestAppDetails()
        val result = mockApi.authenticate(appDetails)

        assertTrueWithContext(
            result.isSuccessful,
            "response.isSuccessful",
            "after authenticate() with valid mock response"
        )
        assertEqualsWithContext(
            expectedGrovsId,
            result.body()?.grovsId,
            "grovsId",
            "after authenticate() returns success"
        )
        assertEqualsWithContext(
            "testapp",
            result.body()?.uriScheme,
            "uriScheme",
            "after authenticate() returns success"
        )
    }

    @Test
    fun `MockGrovsApi authenticate returns 401 error on invalid API key`() = runTest {
        mockApi.authenticateResponse = Response.error(
            401,
            """{"error": "Invalid API key"}""".toResponseBody("application/json".toMediaType())
        )

        val result = mockApi.authenticate(createTestAppDetails())

        assertFalseWithContext(
            result.isSuccessful,
            "response.isSuccessful",
            "after authenticate() with 401 error mock"
        )
        assertEqualsWithContext(
            401,
            result.code(),
            "response.code()",
            "after authenticate() with invalid API key"
        )
    }

    // ==================== Link Generation Tests ====================

    @Test
    fun `MockGrovsApi generateLink returns link URL on successful response`() = runTest {
        val expectedLink = "https://app.grovs.io/abc123"
        mockApi.generateLinkResponse = Response.success(GenerateLinkResponse(link = expectedLink))

        val request = createTestGenerateLinkRequest("Test Title", "Test Subtitle")
        val result = mockApi.generateLink(request)

        assertTrueWithContext(
            result.isSuccessful,
            "response.isSuccessful",
            "after generateLink() with valid mock response"
        )
        assertEqualsWithContext(
            expectedLink,
            result.body()?.link,
            "link",
            "after generateLink() returns success"
        )
        assertTrueWithContext(
            mockApi.verifyGenerateLinkCalled(),
            "generateLink was called",
            "after generateLink() request"
        )
    }

    @Test
    fun `MockGrovsApi generateLink returns 429 error on rate limiting`() = runTest {
        mockApi.generateLinkResponse = Response.error(
            429,
            """{"error": "Rate limit exceeded"}""".toResponseBody("application/json".toMediaType())
        )

        val result = mockApi.generateLink(createTestGenerateLinkRequest())

        assertFalseWithContext(
            result.isSuccessful,
            "response.isSuccessful",
            "after generateLink() with 429 rate limit mock"
        )
        assertEqualsWithContext(
            429,
            result.code(),
            "response.code()",
            "after generateLink() with rate limit exceeded"
        )
    }

    // ==================== Deeplink Payload Tests ====================

    @Test
    fun `MockGrovsApi payloadFor returns DeeplinkDetails with data and tracking on success`() = runTest {
        val testData = mapOf<String, Any>("promo_code" to "SAVE20", "discount" to 20)
        val testTracking = mapOf<String, Any>("utm_source" to "facebook", "utm_campaign" to "summer")

        mockApi.payloadResponse = Response.success(
            DeeplinkDetails(
                link = "https://app.grovs.io/promo",
                data = testData as Map<String, Object>,
                tracking = testTracking as Map<String, Object>
            )
        )

        val result = mockApi.payloadFor(createTestAppDetails())

        assertTrueWithContext(
            result.isSuccessful,
            "response.isSuccessful",
            "after payloadFor() with valid mock response"
        )
        assertEqualsWithContext(
            "https://app.grovs.io/promo",
            result.body()?.link,
            "link",
            "after payloadFor() returns success"
        )
        assertNotNullWithContext(
            result.body()?.data,
            "data",
            "after payloadFor() returns success with data"
        )
        assertNotNullWithContext(
            result.body()?.tracking,
            "tracking",
            "after payloadFor() returns success with tracking"
        )
    }

    @Test
    fun `MockGrovsApi payloadWithLinkFor returns DeeplinkDetails for specific link`() = runTest {
        val link = "https://app.grovs.io/specific"
        val data = mapOf<String, Any>("product_id" to "SKU123") as Map<String, Object>

        mockApi.payloadWithLinkResponse = Response.success(
            DeeplinkDetails(link = link, data = data, tracking = null)
        )

        val result = mockApi.payloadWithLinkFor(createTestAppDetails())

        assertTrueWithContext(
            result.isSuccessful,
            "response.isSuccessful",
            "after payloadWithLinkFor() with valid mock response"
        )
        assertEqualsWithContext(
            link,
            result.body()?.link,
            "link",
            "after payloadWithLinkFor() returns success"
        )
    }

    // ==================== Events Tests ====================

    @Test
    fun `MockGrovsApi addEvent returns success and records event call`() = runTest {
        mockApi.addEventResponse = Response.success(Unit)

        val event = createTestEvent()
        val result = mockApi.addEvent(event)

        assertTrueWithContext(
            result.isSuccessful,
            "response.isSuccessful",
            "after addEvent() with valid mock response"
        )
        assertTrueWithContext(
            mockApi.verifyAddEventCalled(),
            "addEvent was called",
            "after addEvent() request"
        )
    }

    // PURCHASE_EVENT_DISABLED: @Test
    // PURCHASE_EVENT_DISABLED: fun `MockGrovsApi addPaymentEvent returns success and records payment event call`() = runTest {
    // PURCHASE_EVENT_DISABLED:     mockApi.addPaymentEventResponse = Response.success(Unit)
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     val paymentEvent = createTestPaymentEvent(PaymentEventType.BUY, 1999)
    // PURCHASE_EVENT_DISABLED:     val result = mockApi.addPaymentEvent(paymentEvent)
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     assertTrueWithContext(
    // PURCHASE_EVENT_DISABLED:         result.isSuccessful,
    // PURCHASE_EVENT_DISABLED:         "response.isSuccessful",
    // PURCHASE_EVENT_DISABLED:         "after addPaymentEvent() with BUY event"
    // PURCHASE_EVENT_DISABLED:     )
    // PURCHASE_EVENT_DISABLED:     assertTrueWithContext(
    // PURCHASE_EVENT_DISABLED:         mockApi.verifyAddPaymentEventCalled(),
    // PURCHASE_EVENT_DISABLED:         "addPaymentEvent was called",
    // PURCHASE_EVENT_DISABLED:         "after addPaymentEvent() request"
    // PURCHASE_EVENT_DISABLED:     )
    // PURCHASE_EVENT_DISABLED: }

    // ==================== Complete Workflow Tests ====================

    @Test
    fun `Integration workflow device check then authenticate then generateLink succeeds`() = runTest {
        // Step 1: Device check
        mockApi.getDeviceResponse = Response.success(GetDeviceResponse(lastSeen = null))
        val deviceResult = mockApi.getDeviceFor("new_device")
        assertTrueWithContext(
            deviceResult.isSuccessful,
            "device check response.isSuccessful",
            "after getDeviceFor() in workflow step 1"
        )

        // Step 2: Authentication
        mockApi.authenticateResponse = Response.success(
            AuthenticationResponse(grovsId = "grovs_123", uriScheme = "app", sdkIdentifier = null, sdkAttributes = null)
        )
        val authResult = mockApi.authenticate(createTestAppDetails())
        assertTrueWithContext(
            authResult.isSuccessful,
            "auth response.isSuccessful",
            "after authenticate() in workflow step 2"
        )

        // Step 3: Generate link
        mockApi.generateLinkResponse = Response.success(GenerateLinkResponse(link = "https://app.grovs.io/newlink"))
        val linkResult = mockApi.generateLink(createTestGenerateLinkRequest("Share this!"))
        assertTrueWithContext(
            linkResult.isSuccessful,
            "generateLink response.isSuccessful",
            "after generateLink() in workflow step 3"
        )
        assertEqualsWithContext(
            "https://app.grovs.io/newlink",
            linkResult.body()?.link,
            "link",
            "after complete authentication and link generation workflow"
        )
    }

    @Test
    fun `Integration workflow authenticate then payloadWithLinkFor then addEvent succeeds`() = runTest {
        // Step 1: Authenticate
        mockApi.authenticateResponse = Response.success(
            AuthenticationResponse(grovsId = "grovs_456", uriScheme = "app", sdkIdentifier = null, sdkAttributes = null)
        )
        assertTrueWithContext(
            mockApi.authenticate(createTestAppDetails()).isSuccessful,
            "auth response.isSuccessful",
            "after authenticate() in deeplink workflow step 1"
        )

        // Step 2: Get payload for deeplink
        val data = mapOf<String, Any>("promo" to "SUMMER50") as Map<String, Object>
        mockApi.payloadWithLinkResponse = Response.success(
            DeeplinkDetails(link = "https://app.grovs.io/summer", data = data, tracking = null)
        )
        val payloadResult = mockApi.payloadWithLinkFor(createTestAppDetails())
        assertTrueWithContext(
            payloadResult.isSuccessful,
            "payload response.isSuccessful",
            "after payloadWithLinkFor() in deeplink workflow step 2"
        )
        assertEqualsWithContext(
            "SUMMER50",
            payloadResult.body()?.data?.get("promo"),
            "data['promo']",
            "after payloadWithLinkFor() returns promo data"
        )

        // Step 3: Track event
        mockApi.addEventResponse = Response.success(Unit)
        val eventResult = mockApi.addEvent(createTestEvent())
        assertTrueWithContext(
            eventResult.isSuccessful,
            "addEvent response.isSuccessful",
            "after addEvent() in deeplink workflow step 3"
        )
    }

    // ==================== Helper Methods ====================

    private fun createTestAppDetails() = io.grovs.model.AppDetails(
        version = "1.0.0",
        build = "1",
        bundle = "io.grovs.test",
        device = "TestDevice",
        deviceID = "test_device_id_123",
        userAgent = "TestUserAgent/1.0"
    )

    private fun createTestGenerateLinkRequest(
        title: String = "Test Link",
        subtitle: String? = null
    ) = io.grovs.model.GenerateLinkRequest(
        title = title,
        subtitle = subtitle,
        imageUrl = null,
        data = null,
        tags = null,
        iosCustomRedirect = null,
        androidCustomRedirect = null,
        desktopCustomRedirect = null,
        showPreviewIos = null,
        showPreviewAndroid = null,
        trackingCampaign = null,
        trackingMedium = null,
        trackingSource = null
    )

    private fun createTestEvent() = io.grovs.model.Event(
        event = io.grovs.model.EventType.APP_OPEN,
        createdAt = InstantCompat.now(),
        link = null,
        engagementTime = null
    )

    // PURCHASE_EVENT_DISABLED: private fun createTestPaymentEvent(
    // PURCHASE_EVENT_DISABLED:     type: PaymentEventType = PaymentEventType.BUY,
    // PURCHASE_EVENT_DISABLED:     priceCents: Long = 999
    // PURCHASE_EVENT_DISABLED: ) = io.grovs.model.events.PaymentEvent(
    // PURCHASE_EVENT_DISABLED:     eventType = type,
    // PURCHASE_EVENT_DISABLED:     appId = "io.grovs.test",
    // PURCHASE_EVENT_DISABLED:     priceCents = priceCents,
    // PURCHASE_EVENT_DISABLED:     currency = "USD",
    // PURCHASE_EVENT_DISABLED:     date = InstantCompat.now(),
    // PURCHASE_EVENT_DISABLED:     transactionToken = "txn_123",
    // PURCHASE_EVENT_DISABLED:     originalTransactionId = null,
    // PURCHASE_EVENT_DISABLED:     productId = "test_product",
    // PURCHASE_EVENT_DISABLED:     store = false,
    // PURCHASE_EVENT_DISABLED:     link = null
    // PURCHASE_EVENT_DISABLED: )
}
