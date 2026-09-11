package io.grovs.service

import android.content.Context
import io.grovs.TestFixtures
import io.grovs.MockGrovsApi
import io.grovs.api.GrovsApi
import io.grovs.handlers.ConsentToken
import io.grovs.TestAssertions.assertEqualsWithContext
import io.grovs.TestAssertions.assertNotNullWithContext
import io.grovs.TestAssertions.assertTrueWithContext
import io.grovs.TestAssertions.assertResultSuccess
import io.grovs.TestAssertions.assertResultError
import io.grovs.handlers.GrovsContext
import io.grovs.model.AuthenticationResponse
import io.grovs.model.DebugLogger
import io.grovs.model.DeeplinkDetails
import io.grovs.model.GenerateLinkResponse
import io.grovs.model.LogLevel
import io.grovs.model.notifications.NotificationsResponse
import io.grovs.model.notifications.NumberOfUnreadNotificationsResponse
import io.grovs.utils.GVRetryResult
import io.grovs.utils.LSResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.IOException

/**
 * Core unit tests for GrovsService.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GrovsServiceTest {

    private lateinit var context: Context
    private lateinit var grovsContext: GrovsContext
    private lateinit var mockGrovsApi: MockGrovsApi
    private lateinit var grovsService: GrovsService

    private val testApiKey = "test-api-key-123"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()

        grovsContext = GrovsContext()
        grovsContext.settings.sdkEnabled = true

        mockGrovsApi = MockGrovsApi()

        DebugLogger.instance.logLevel = LogLevel.INFO

        grovsService = GrovsService(
            context = context,
            apiKey = testApiKey,
            grovsContext = grovsContext
        )
        installApi(mockGrovsApi)
    }

    private fun installApi(api: GrovsApi) {
        // Inject only the transport boundary; all response handling runs in production code.
        GrovsService::class.java.getDeclaredField("grovsApi").apply {
            isAccessible = true
            set(grovsService, api)
        }
    }

    // ==================== authenticate Tests ====================

    @Test
    fun `GrovsService authenticate returns GVRetryResult Success with grovsId on successful API response`() = runTest {
        val expectedResponse = AuthenticationResponse(
            grovsId = "grovs_123",
            uriScheme = "testscheme",
            sdkIdentifier = "user_123",
            sdkAttributes = null
        )
        mockGrovsApi.authenticateResponse = Response.success(expectedResponse)

        val appDetails = TestFixtures.createAppDetails()
        val results = grovsService.authenticate(appDetails).take(1).toList()

        assertEqualsWithContext(
            1,
            results.size,
            "results.size",
            "after authenticate() with successful mock response"
        )
        assertTrueWithContext(
            results[0] is GVRetryResult.Success,
            "result is GVRetryResult.Success",
            "after authenticate() with successful mock response"
        )
        assertEqualsWithContext(
            "grovs_123",
            (results[0] as GVRetryResult.Success).data.grovsId,
            "grovsId",
            "after authenticate() returns success"
        )
    }

    @Test
    fun `GrovsService authenticate returns GVRetryResult Error on 401 API response`() = runTest {
        mockGrovsApi.authenticateResponse = MockGrovsApi.createErrorResponseTyped(401, "Invalid API key")

        val appDetails = TestFixtures.createAppDetails()
        val results = grovsService.authenticate(appDetails).take(1).toList()

        assertEqualsWithContext(
            1,
            results.size,
            "results.size",
            "after authenticate() with 401 error response"
        )
        assertTrueWithContext(
            results[0] is GVRetryResult.Error,
            "result is GVRetryResult.Error",
            "after authenticate() with 401 error response"
        )
    }

    // ==================== generateLink Tests ====================

    @Test
    fun `GrovsService generateLink returns LSResult Success with link URL on successful API response`() = runTest {
        val expectedLink = "https://example.grovs.io/generated123"
        mockGrovsApi.generateLinkResponse = Response.success(GenerateLinkResponse(link = expectedLink))

        val result = grovsService.generateLink(
            title = "Test Title",
            subtitle = "Test Subtitle",
            imageURL = "https://example.com/image.png",
            data = mapOf("key" to "value"),
            tags = listOf("tag1", "tag2"),
            customRedirects = null,
            showPreviewIos = true,
            showPreviewAndroid = true,
            copyToClipboardIos = null,
            copyToClipboardAndroid = null,
            tracking = null
        )

        val response = assertResultSuccess(
            result,
            context = "after generateLink() with successful mock response"
        )
        assertEqualsWithContext(
            expectedLink,
            response.link,
            "link",
            "after generateLink() returns success"
        )
    }

    @Test
    fun `GrovsService generateLink returns LSResult Error on 400 API response`() = runTest {
        mockGrovsApi.generateLinkResponse = MockGrovsApi.createErrorResponseTyped(400, "Invalid parameters")

        val result = grovsService.generateLink(
            title = "Test",
            subtitle = null,
            imageURL = null,
            data = null,
            tags = null,
            customRedirects = null,
            showPreviewIos = null,
            showPreviewAndroid = null,
            copyToClipboardIos = null,
            copyToClipboardAndroid = null,
            tracking = null
        )

        assertResultError(
            result,
            context = "after generateLink() with 400 error response"
        )
    }

    // ==================== payloadFor Tests ====================

    @Test
    fun `GrovsService payloadFor returns LSResult Success with DeeplinkDetails on successful response`() = runTest {
        val expectedDetails = DeeplinkDetails(
            link = "https://example.grovs.io/link123",
            data = mapOf("key" to "value" as Object),
            tracking = mapOf("campaign" to "test" as Object)
        )
        mockGrovsApi.payloadResponse = Response.success(expectedDetails)

        val result = grovsService.payloadFor(TestFixtures.createAppDetails())

        val response = assertResultSuccess(
            result,
            context = "after payloadFor() with successful mock response"
        )
        assertEqualsWithContext(
            "https://example.grovs.io/link123",
            response.link,
            "link",
            "after payloadFor() returns success"
        )
    }

    @Test
    fun `GrovsService payloadFor returns LSResult Error on 404 API response`() = runTest {
        mockGrovsApi.payloadResponse = MockGrovsApi.createErrorResponseTyped(404, "Not found")

        val result = grovsService.payloadFor(TestFixtures.createAppDetails())

        assertResultError(
            result,
            context = "after payloadFor() with 404 error response"
        )
    }

    // ==================== linkDetails Tests ====================

    @Test
    fun `GrovsService linkDetails returns LSResult Success with link data on successful response`() = runTest {
        val jsonResponse = """{"title": "My Link", "description": "A test link", "data": {"key": "value"}}"""
        mockGrovsApi.linkDetailsResponse = Response.success(jsonResponse.toResponseBody("application/json".toMediaType()))

        val result = grovsService.linkDetails("/abc123")

        val linkResponse = assertResultSuccess(
            result,
            context = "after linkDetails('/abc123') with successful mock response"
        )
        assertNotNullWithContext(
            linkResponse.link,
            "link",
            "after linkDetails() returns success"
        )
    }

    @Test
    fun `GrovsService linkDetails returns LSResult Error on 404 API response`() = runTest {
        mockGrovsApi.linkDetailsResponse = MockGrovsApi.createErrorResponseTyped(404, "Link not found")

        val result = grovsService.linkDetails("/nonexistent")

        assertResultError(
            result,
            context = "after linkDetails('/nonexistent') with 404 error response"
        )
    }

    // ==================== notifications Tests ====================

    @Test
    fun `GrovsService notifications returns LSResult Success with notification list on successful response`() = runTest {
        mockGrovsApi.notificationsResponse = Response.success(NotificationsResponse(notifications = emptyList()))

        val result = grovsService.notifications(page = 1)

        val response = assertResultSuccess(
            result,
            context = "after notifications(page=1) with successful mock response"
        )
        assertNotNullWithContext(
            response.notifications,
            "notifications",
            "after notifications() returns success"
        )
    }

    @Test
    fun `GrovsService numberOfUnreadNotifications returns LSResult Success with count on successful response`() = runTest {
        mockGrovsApi.numberOfUnreadNotificationsResponse = Response.success(
            NumberOfUnreadNotificationsResponse(numberOfUnreadNotifications = 5)
        )
        var attempts = 0
        installApi(object : GrovsApi by mockGrovsApi {
            override suspend fun numberOfUnreadNotifications(token: ConsentToken): Response<NumberOfUnreadNotificationsResponse> {
                if (attempts++ == 0) throw IOException("Transient connection failure")
                return mockGrovsApi.numberOfUnreadNotifications(token)
            }
        })

        val result = grovsService.numberOfUnreadNotifications()

        val response = assertResultSuccess(
            result,
            context = "after numberOfUnreadNotifications() with successful mock response"
        )
        assertEqualsWithContext(
            5,
            response.numberOfUnreadNotifications,
            "numberOfUnreadNotifications",
            "after numberOfUnreadNotifications() returns success"
        )
        assertEqualsWithContext(2, attempts, "attempts", "after recovering from a transport failure")
    }

    @Test
    fun `GrovsService markNotificationAsRead returns LSResult Success on successful API response`() = runTest {
        mockGrovsApi.markNotificationAsReadResponse = Response.success(Unit)

        val result = grovsService.markNotificationAsRead(notificationId = 123)

        assertResultSuccess(
            result,
            context = "after markNotificationAsRead(123) with successful mock response"
        )
    }

    @Test
    fun `GrovsService addPaymentEvent reports a refused payment with its status`() = runTest {
        mockGrovsApi.addPaymentEventResponse = MockGrovsApi.createErrorResponseTyped(422, "Validation failed: Currency can't be blank")

        val result = grovsService.addPaymentEvent(io.grovs.model.events.PaymentEvent(productId = "sku"))

        val failure = (result as io.grovs.utils.LSResult.Error).exception as HttpStatusException
        assertEqualsWithContext(422, failure.code, "failure.code", "after a 422 payment response")
    }

    @Test
    fun `GrovsService addPaymentEvent treats a 2xx without a body as delivered`() = runTest {
        mockGrovsApi.addPaymentEventResponse = Response.success<Unit>(null)

        val result = grovsService.addPaymentEvent(io.grovs.model.events.PaymentEvent(productId = "sku"))

        assertResultSuccess(result, context = "after a 2xx payment response with no body")
    }
}
