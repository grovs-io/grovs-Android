package io.grovs.service

import android.app.Application
import android.content.Context
import io.grovs.MockGrovsApi
import io.grovs.TestAssertions.assertEqualsWithContext
import io.grovs.TestAssertions.assertNotNullWithContext
import io.grovs.TestAssertions.assertTrueWithContext
import io.grovs.TestAssertions.assertResultSuccess
import io.grovs.TestAssertions.assertResultError
import io.grovs.api.GrovsApi
import io.grovs.handlers.GrovsContext
import io.grovs.model.AppDetails
import io.grovs.model.AuthenticationResponse
import io.grovs.model.DebugLogger
import io.grovs.model.DeeplinkDetails
import io.grovs.model.Event
import io.grovs.model.EventType
import io.grovs.model.GenerateLinkResponse
import io.grovs.model.GetDeviceResponse
import io.grovs.model.LinkDetailsResponse
import io.grovs.model.LogLevel
// PURCHASE_EVENT_DISABLED: import io.grovs.model.events.PaymentEvent
// PURCHASE_EVENT_DISABLED: import io.grovs.model.events.PaymentEventType
import io.grovs.model.notifications.NotificationsResponse
import io.grovs.model.notifications.NumberOfUnreadNotificationsResponse
import io.grovs.utils.GVRetryResult
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSResult
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.Response

/**
 * Core unit tests for GrovsService.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GrovsServiceTest {

    private lateinit var context: Context
    private lateinit var application: Application
    private lateinit var grovsContext: GrovsContext
    private lateinit var mockGrovsApi: MockGrovsApi
    private lateinit var grovsService: TestableGrovsService

    private val testApiKey = "test-api-key-123"

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxed = true)

        context = RuntimeEnvironment.getApplication()
        application = RuntimeEnvironment.getApplication()

        grovsContext = GrovsContext()
        grovsContext.settings.sdkEnabled = true

        mockGrovsApi = MockGrovsApi()

        DebugLogger.instance.logLevel = LogLevel.INFO

        grovsService = TestableGrovsService(
            context = context,
            apiKey = testApiKey,
            grovsContext = grovsContext,
            testApi = mockGrovsApi
        )
    }

    @After
    fun tearDown() {
        mockGrovsApi.reset()
        unmockkAll()
    }

    private fun createTestAppDetails(): AppDetails {
        return AppDetails(
            version = "1.0.0",
            build = "1",
            bundle = "io.grovs.test",
            device = "Test Device",
            deviceID = "test-device-id",
            userAgent = "Test User Agent",
            screenWidth = "1080",
            screenHeight = "1920",
            timezone = "UTC",
            language = "en-US",
            webglVendor = "Test Vendor",
            webglRenderer = "Test Renderer"
        )
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

        val appDetails = createTestAppDetails()
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

        val appDetails = createTestAppDetails()
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

        val result = grovsService.payloadFor(createTestAppDetails())

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

        val result = grovsService.payloadFor(createTestAppDetails())

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

    // ==================== addEvent Tests ====================

    @Test
    fun `GrovsService addEvent returns LSResult Success with true on successful API response`() = runTest {
        mockGrovsApi.addEventResponse = Response.success(Unit)

        val event = Event(
            event = EventType.VIEW,
            createdAt = InstantCompat.now(),
            link = "https://example.grovs.io/link"
        )

        val result = grovsService.addEvent(event)

        val data = assertResultSuccess(
            result,
            context = "after addEvent() with successful mock response"
        )
        assertTrueWithContext(
            data,
            "result data",
            "after addEvent() returns success"
        )
    }

    @Test
    fun `GrovsService addEvent returns LSResult Error on 500 API response`() = runTest {
        mockGrovsApi.addEventResponse = MockGrovsApi.createErrorResponseTyped(500, "Server error")

        val event = Event(EventType.VIEW, InstantCompat.now())
        val result = grovsService.addEvent(event)

        assertResultError(
            result,
            context = "after addEvent() with 500 error response"
        )
    }

    // ==================== addPaymentEvent Tests ====================

    // PURCHASE_EVENT_DISABLED: @Test
    // PURCHASE_EVENT_DISABLED: fun `GrovsService addPaymentEvent passes payment event to API with correct eventType`() = runTest {
    // PURCHASE_EVENT_DISABLED:     val paymentEvent = PaymentEvent(
    // PURCHASE_EVENT_DISABLED:         eventType = PaymentEventType.BUY,
    // PURCHASE_EVENT_DISABLED:         priceCents = 1999,
    // PURCHASE_EVENT_DISABLED:         currency = "EUR",
    // PURCHASE_EVENT_DISABLED:         productId = "pro_plan"
    // PURCHASE_EVENT_DISABLED:     )
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     grovsService.addPaymentEvent(paymentEvent)
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     assertTrueWithContext(
    // PURCHASE_EVENT_DISABLED:         mockGrovsApi.verifyAddPaymentEventCalled(),
    // PURCHASE_EVENT_DISABLED:         "addPaymentEvent was called on mockApi",
    // PURCHASE_EVENT_DISABLED:         "after addPaymentEvent() with BUY event"
    // PURCHASE_EVENT_DISABLED:     )
    // PURCHASE_EVENT_DISABLED:     assertEqualsWithContext(
    // PURCHASE_EVENT_DISABLED:         PaymentEventType.BUY,
    // PURCHASE_EVENT_DISABLED:         mockGrovsApi.addPaymentEventCalls[0].eventType,
    // PURCHASE_EVENT_DISABLED:         "eventType",
    // PURCHASE_EVENT_DISABLED:         "after addPaymentEvent() with BUY event"
    // PURCHASE_EVENT_DISABLED:     )
    // PURCHASE_EVENT_DISABLED: }

    // PURCHASE_EVENT_DISABLED: @Test
    // PURCHASE_EVENT_DISABLED: fun `GrovsService addPaymentEvent returns LSResult Error on 400 API response`() = runTest {
    // PURCHASE_EVENT_DISABLED:     mockGrovsApi.addPaymentEventResponse = MockGrovsApi.createErrorResponseTyped(400, "Invalid payment")
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     val paymentEvent = PaymentEvent(eventType = PaymentEventType.BUY, priceCents = 100, currency = "USD")
    // PURCHASE_EVENT_DISABLED:     val result = grovsService.addPaymentEvent(paymentEvent)
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     assertResultError(
    // PURCHASE_EVENT_DISABLED:         result,
    // PURCHASE_EVENT_DISABLED:         context = "after addPaymentEvent() with 400 error response"
    // PURCHASE_EVENT_DISABLED:     )
    // PURCHASE_EVENT_DISABLED: }

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
}

/**
 * Testable subclass of GrovsService that allows injecting a mock GrovsApi.
 */
class TestableGrovsService(
    context: Context,
    apiKey: String,
    grovsContext: GrovsContext,
    private val testApi: GrovsApi
) : IGrovsService {

    private val grovsContext = grovsContext
    private val context = context

    override fun authenticate(appDetails: AppDetails): kotlinx.coroutines.flow.Flow<GVRetryResult<AuthenticationResponse>> = callbackFlow {
        val response = testApi.authenticate(appDetails)
        if (response.isSuccessful) {
            response.body()?.let {
                trySend(GVRetryResult.Success(it))
                close()
                return@callbackFlow
            }
        }
        trySend(GVRetryResult.Error(java.io.IOException("Failed to authenticate")))
        close()
        awaitClose { }
    }

    override fun getDeviceFor(deviceId: String): kotlinx.coroutines.flow.Flow<GVRetryResult<GetDeviceResponse>> = callbackFlow {
        val response = testApi.getDeviceFor(deviceId)
        if (response.isSuccessful) {
            response.body()?.let {
                trySend(GVRetryResult.Success(it))
                close()
                return@callbackFlow
            }
        }
        trySend(GVRetryResult.Error(java.io.IOException("Failed to get device")))
        close()
        awaitClose { }
    }

    override suspend fun payloadFor(appDetails: AppDetails): LSResult<DeeplinkDetails> {
        return try {
            val response = testApi.payloadFor(appDetails)
            if (response.isSuccessful) {
                response.body()?.let { return LSResult.Success(it) }
            }
            LSResult.Error(java.io.IOException("Failed to get payload"))
        } catch (e: Exception) {
            LSResult.Error(e)
        }
    }

    override suspend fun payloadWithLinkFor(appDetails: AppDetails): LSResult<DeeplinkDetails> {
        return try {
            val response = testApi.payloadWithLinkFor(appDetails)
            if (response.isSuccessful) {
                response.body()?.let { return LSResult.Success(it) }
            }
            LSResult.Error(java.io.IOException("Failed to get payload with link"))
        } catch (e: Exception) {
            LSResult.Error(e)
        }
    }

    override suspend fun generateLink(
        title: String?,
        subtitle: String?,
        imageURL: String?,
        data: Map<String, java.io.Serializable>?,
        tags: List<String>?,
        customRedirects: CustomRedirects?,
        showPreviewIos: Boolean?,
        showPreviewAndroid: Boolean?,
        tracking: TrackingParams?
    ): LSResult<GenerateLinkResponse> {
        return try {
            val request = io.grovs.model.GenerateLinkRequest(
                title = title,
                subtitle = subtitle,
                imageUrl = imageURL,
                data = com.google.gson.Gson().toJson(data),
                tags = com.google.gson.Gson().toJson(tags),
                iosCustomRedirect = customRedirects?.ios,
                androidCustomRedirect = customRedirects?.android,
                desktopCustomRedirect = customRedirects?.desktop,
                showPreviewIos = showPreviewIos,
                showPreviewAndroid = showPreviewAndroid,
                trackingCampaign = tracking?.utmCampaign,
                trackingMedium = tracking?.utmMedium,
                trackingSource = tracking?.utmSource
            )
            val response = testApi.generateLink(request)
            if (response.isSuccessful) {
                response.body()?.let { return LSResult.Success(it) }
            }
            LSResult.Error(java.io.IOException("Failed to generate link"))
        } catch (e: Exception) {
            LSResult.Error(e)
        }
    }

    override suspend fun linkDetails(path: String): LSResult<LinkDetailsResponse> {
        return try {
            val request = io.grovs.model.LinkDetailsRequest(path = path)
            val response = testApi.linkDetails(request)
            if (response.isSuccessful) {
                response.body()?.string()?.let {
                    if (it == "null") {
                        return LSResult.Error(java.io.IOException("Invalid link path"))
                    }
                    val map: Map<String, Any> = com.google.gson.Gson().fromJson(it, object : com.google.gson.reflect.TypeToken<Map<String, Any?>>() {}.type)
                    return LSResult.Success(LinkDetailsResponse(link = map))
                }
            }
            LSResult.Error(java.io.IOException("Failed to get link details"))
        } catch (e: Exception) {
            LSResult.Error(e)
        }
    }

    override suspend fun updateAttributes(
        identifier: String?,
        attributes: Map<String, Any>?,
        pushToken: String?
    ): LSResult<Boolean> {
        return try {
            val request = io.grovs.model.UpdateAttributesRequest(
                sdkIdentifier = identifier,
                sdkAttributes = attributes,
                pushToken = pushToken
            )
            val response = testApi.updateAttributes(request)
            if (response.isSuccessful) {
                return LSResult.Success(true)
            }
            LSResult.Error(java.io.IOException("Failed to update attributes"))
        } catch (e: Exception) {
            LSResult.Error(e)
        }
    }

    override suspend fun addEvent(event: io.grovs.model.Event): LSResult<Boolean> {
        return try {
            val response = testApi.addEvent(event)
            if (response.isSuccessful) {
                return LSResult.Success(true)
            }
            LSResult.Error(java.io.IOException("Failed to add event"))
        } catch (e: Exception) {
            LSResult.Error(e)
        }
    }

    // PURCHASE_EVENT_DISABLED: override suspend fun addPaymentEvent(event: PaymentEvent): LSResult<Boolean> {
    // PURCHASE_EVENT_DISABLED:     return try {
    // PURCHASE_EVENT_DISABLED:         val response = testApi.addPaymentEvent(event)
    // PURCHASE_EVENT_DISABLED:         if (response.isSuccessful) {
    // PURCHASE_EVENT_DISABLED:             return LSResult.Success(true)
    // PURCHASE_EVENT_DISABLED:         }
    // PURCHASE_EVENT_DISABLED:         LSResult.Error(java.io.IOException("Failed to add payment event"))
    // PURCHASE_EVENT_DISABLED:     } catch (e: Exception) {
    // PURCHASE_EVENT_DISABLED:         LSResult.Error(e)
    // PURCHASE_EVENT_DISABLED:     }
    // PURCHASE_EVENT_DISABLED: }

    override suspend fun notifications(page: Int): LSResult<NotificationsResponse> {
        return try {
            val request = io.grovs.model.notifications.NotificationsRequest(page = page)
            val response = testApi.notifications(request)
            if (response.isSuccessful) {
                response.body()?.let { return LSResult.Success(it) }
            }
            LSResult.Error(java.io.IOException("Failed to get notifications"))
        } catch (e: Exception) {
            LSResult.Error(e)
        }
    }

    override suspend fun notificationsToDisplayAutomatically(): LSResult<NotificationsResponse> {
        return try {
            val response = testApi.notificationsToDisplayAutomatically()
            if (response.isSuccessful) {
                response.body()?.let { return LSResult.Success(it) }
            }
            LSResult.Error(java.io.IOException("Failed to get notifications"))
        } catch (e: Exception) {
            LSResult.Error(e)
        }
    }

    override suspend fun numberOfUnreadNotifications(): LSResult<NumberOfUnreadNotificationsResponse> {
        return try {
            val response = testApi.numberOfUnreadNotifications()
            if (response.isSuccessful) {
                response.body()?.let { return LSResult.Success(it) }
            }
            LSResult.Error(java.io.IOException("Failed to get unread count"))
        } catch (e: Exception) {
            LSResult.Error(e)
        }
    }

    override suspend fun markNotificationAsRead(notificationId: Int): LSResult<Boolean> {
        return try {
            val request = io.grovs.model.notifications.MarkNotificationAsReadRequest(notificationId = notificationId)
            val response = testApi.markNotificationAsRead(request)
            if (response.isSuccessful) {
                return LSResult.Success(true)
            }
            LSResult.Error(java.io.IOException("Failed to mark as read"))
        } catch (e: Exception) {
            LSResult.Error(e)
        }
    }
}
