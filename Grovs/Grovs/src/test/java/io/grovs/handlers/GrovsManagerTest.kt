package io.grovs.handlers

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.grovs.TestAssertions.assertAuthenticated
import io.grovs.TestAssertions.assertUnauthenticated
import io.grovs.TestAssertions.assertEqualsWithContext
import io.grovs.TestAssertions.assertNotNullWithContext
import io.grovs.TestAssertions.assertNullWithContext
import io.grovs.TestAssertions.assertResultSuccess
import io.grovs.TestAssertions.assertResultError
import io.grovs.TestAssertions.assertResultErrorContains
import io.grovs.model.AppDetails
import io.grovs.model.DebugLogger
import io.grovs.model.DeeplinkDetails
import io.grovs.model.GenerateLinkResponse
import io.grovs.model.LinkDetailsResponse
import io.grovs.model.LogLevel
// PURCHASE_EVENT_DISABLED: import io.grovs.model.events.PaymentEventType
import io.grovs.service.IGrovsService
import io.grovs.utils.IAppDetailsHelper
import io.grovs.utils.LSResult
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.Serializable

/**
 * Core unit tests for GrovsManager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GrovsManagerTest {

    private lateinit var context: Context
    private lateinit var application: Application
    private lateinit var grovsContext: GrovsContext
    private lateinit var mockGrovsService: IGrovsService
    private lateinit var mockEventsManager: IEventsManager
    private lateinit var mockAppDetailsHelper: IAppDetailsHelper
    private lateinit var grovsManager: GrovsManager

    private val testApiKey = "test-api-key-123"

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxed = true)

        context = RuntimeEnvironment.getApplication()
        application = RuntimeEnvironment.getApplication()

        grovsContext = GrovsContext()
        grovsContext.settings.sdkEnabled = true

        mockGrovsService = mockk(relaxed = true)
        mockEventsManager = mockk(relaxed = true)
        mockAppDetailsHelper = mockk(relaxed = true)

        coEvery { mockAppDetailsHelper.toAppDetails() } returns createMockAppDetails()
        every { mockAppDetailsHelper.deviceID } returns "test-device-id"
        every { mockAppDetailsHelper.versionName } returns "1.0.0"
        every { mockAppDetailsHelper.versionCode } returns 1
        every { mockAppDetailsHelper.applicationId } returns "io.grovs.test"
        every { mockAppDetailsHelper.device } returns "Test Device"

        DebugLogger.instance.logLevel = LogLevel.INFO

        grovsManager = GrovsManager(
            context = context,
            application = application,
            grovsContext = grovsContext,
            apiKey = testApiKey,
            grovsService = mockGrovsService,
            eventsManager = mockEventsManager,
            appDetailsHelper = mockAppDetailsHelper
        )
    }

    private fun createMockAppDetails(): AppDetails {
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

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== Authentication State Tests ====================

    @Test
    fun `GrovsManager authenticationState is UNAUTHENTICATED when newly constructed`() {
        assertUnauthenticated(
            grovsManager,
            context = "after construction with default settings"
        )
    }

    // ==================== Properties Tests ====================

    @Test
    fun `GrovsManager identifier property updates grovsContext when set`() {
        grovsManager.identifier = "user-123"

        assertEqualsWithContext(
            "user-123",
            grovsContext.identifier,
            "grovsContext.identifier",
            "after setting grovsManager.identifier='user-123'"
        )
    }

    @Test
    fun `GrovsManager pushToken property updates grovsContext when set`() {
        grovsManager.pushToken = "fcm-token-xyz"

        assertEqualsWithContext(
            "fcm-token-xyz",
            grovsContext.pushToken,
            "grovsContext.pushToken",
            "after setting grovsManager.pushToken='fcm-token-xyz'"
        )
    }

    @Test
    fun `GrovsManager attributes property updates grovsContext when set`() {
        val attrs = mapOf("key1" to "value1", "key2" to 42)

        grovsManager.attributes = attrs

        assertEqualsWithContext(
            attrs,
            grovsContext.attributes,
            "grovsContext.attributes",
            "after setting grovsManager.attributes with key1='value1', key2=42"
        )
    }

    // ==================== Lifecycle Tests ====================

    @Test
    fun `GrovsManager onAppForegrounded delegates to eventsManager`() = runTest {
        grovsManager.onAppForegrounded()

        coVerify { mockEventsManager.onAppForegrounded() }
    }

    @Test
    fun `GrovsManager onAppBackgrounded delegates to eventsManager`() {
        grovsManager.onAppBackgrounded()

        verify { mockEventsManager.onAppBackgrounded() }
    }

    // ==================== Generate Link Tests ====================

    @Test
    fun `GrovsManager generateLink returns LSResult Error when SDK is disabled`() = runTest {
        grovsContext.settings.sdkEnabled = false

        val result = grovsManager.generateLink(
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
            context = "after generateLink() with sdkEnabled=false"
        )
    }

    @Test
    fun `GrovsManager generateLink returns LSResult Error with not ready message when unauthenticated`() = runTest {
        assertUnauthenticated(grovsManager, context = "before generateLink() call")

        val result = grovsManager.generateLink(
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

        assertResultErrorContains(
            result,
            expectedMessageContains = "not ready",
            context = "after generateLink() with UNAUTHENTICATED state"
        )
    }

    @Test
    fun `GrovsManager generateLink calls service and returns Success when authenticated`() = runTest {
        grovsManager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED

        val expectedResponse = GenerateLinkResponse(
            link = "https://test.grovs.io/abc123"
        )

        coEvery {
            mockGrovsService.generateLink(
                title = any(),
                subtitle = any(),
                imageURL = any(),
                data = any(),
                tags = any(),
                customRedirects = any(),
                showPreviewIos = any(),
                showPreviewAndroid = any(),
                tracking = any()
            )
        } returns LSResult.Success(expectedResponse)

        val result = grovsManager.generateLink(
            title = "Test Link",
            subtitle = "Subtitle",
            imageURL = "https://example.com/image.png",
            data = mapOf("key" to "value" as Serializable),
            tags = listOf("tag1", "tag2"),
            customRedirects = null,
            showPreviewIos = true,
            showPreviewAndroid = false,
            tracking = null
        )

        val response = assertResultSuccess(
            result,
            context = "after generateLink() with AUTHENTICATED state"
        )
        assertEqualsWithContext(
            "https://test.grovs.io/abc123",
            response.link,
            "link",
            "after generateLink() returns success"
        )

        coVerify {
            mockGrovsService.generateLink(
                title = "Test Link",
                subtitle = "Subtitle",
                imageURL = "https://example.com/image.png",
                data = any(),
                tags = listOf("tag1", "tag2"),
                customRedirects = null,
                showPreviewIos = true,
                showPreviewAndroid = false,
                tracking = null
            )
        }
    }

    // ==================== Link Details Tests ====================

    @Test
    fun `GrovsManager linkDetails returns LSResult Error when SDK is disabled`() = runTest {
        grovsContext.settings.sdkEnabled = false

        val result = grovsManager.linkDetails("/test-path")

        assertResultError(
            result,
            context = "after linkDetails() with sdkEnabled=false"
        )
    }

    @Test
    fun `GrovsManager linkDetails calls service and returns Success when authenticated`() = runTest {
        grovsManager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED

        val expectedResponse = LinkDetailsResponse(
            link = mapOf("url" to "https://test.grovs.io/path", "title" to "Test Title")
        )

        coEvery { mockGrovsService.linkDetails(any()) } returns LSResult.Success(expectedResponse)

        val result = grovsManager.linkDetails("/path")

        val response = assertResultSuccess(
            result,
            context = "after linkDetails('/path') with AUTHENTICATED state"
        )
        assertEqualsWithContext(
            "Test Title",
            response.link["title"],
            "link['title']",
            "after linkDetails() returns success"
        )

        coVerify { mockGrovsService.linkDetails("/path") }
    }

    // ==================== Handle Intent Tests ====================

    @Test
    fun `GrovsManager handleIntent returns null when not authenticated`() = runTest {
        val intent = Intent()

        val result = grovsManager.handleIntent(intent, delayEvents = false)

        assertNullWithContext(
            result,
            "handleIntent result",
            "after handleIntent() with UNAUTHENTICATED state"
        )
    }

    @Test
    fun `GrovsManager handleIntent with data URI calls payloadWithLinkFor and returns DeeplinkDetails`() = runTest {
        grovsManager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED

        val intent = Intent().apply {
            data = Uri.parse("https://test.grovs.io/deep/link")
        }

        val expectedDetails = DeeplinkDetails(
            link = "https://test.grovs.io/deep/link",
            data = mapOf("key" to "value" as Object),
            tracking = null
        )

        coEvery { mockGrovsService.payloadWithLinkFor(any()) } returns LSResult.Success(expectedDetails)
        coEvery { mockEventsManager.setLinkToNewFutureActions(any(), any()) } just Runs

        val result = grovsManager.handleIntent(intent, delayEvents = false)

        assertNotNullWithContext(
            result,
            "handleIntent result",
            "after handleIntent() with data URI and AUTHENTICATED state"
        )
        assertEqualsWithContext(
            "https://test.grovs.io/deep/link",
            result?.link,
            "link",
            "after handleIntent() with data URI"
        )

        coVerify { mockEventsManager.setLinkToNewFutureActions(any(), delayEvents = false) }
        coVerify { mockGrovsService.payloadWithLinkFor(any()) }
    }

    @Test
    fun `GrovsManager handleIntent returns DeeplinkDetails with data and tracking when present`() = runTest {
        grovsManager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED

        val intent = Intent().apply {
            data = Uri.parse("https://test.grovs.io/promo")
        }

        val expectedDetails = DeeplinkDetails(
            link = "https://test.grovs.io/promo",
            data = mapOf("promo" to "summer2024" as Object),
            tracking = mapOf("campaign" to "email" as Object)
        )

        coEvery { mockGrovsService.payloadWithLinkFor(any()) } returns LSResult.Success(expectedDetails)
        coEvery { mockEventsManager.setLinkToNewFutureActions(any(), any()) } just Runs

        val result = grovsManager.handleIntent(intent, delayEvents = false)

        assertNotNullWithContext(
            result,
            "handleIntent result",
            "after handleIntent() with promo link"
        )
        assertEqualsWithContext(
            "https://test.grovs.io/promo",
            result?.link,
            "link",
            "after handleIntent() with promo link"
        )
        assertEqualsWithContext(
            "summer2024",
            result?.data?.get("promo"),
            "data['promo']",
            "after handleIntent() with promo data"
        )
        assertEqualsWithContext(
            "email",
            result?.tracking?.get("campaign"),
            "tracking['campaign']",
            "after handleIntent() with tracking data"
        )
    }

    @Test
    fun `GrovsManager handleIntent returns null when service returns error`() = runTest {
        grovsManager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED

        val intent = Intent().apply {
            data = Uri.parse("https://test.grovs.io/error")
        }

        coEvery { mockGrovsService.payloadWithLinkFor(any()) } returns LSResult.Error(Exception("Network error"))
        coEvery { mockEventsManager.setLinkToNewFutureActions(any(), any()) } just Runs

        val result = grovsManager.handleIntent(intent, delayEvents = false)

        assertNullWithContext(
            result,
            "handleIntent result",
            "after handleIntent() when service returns error"
        )
    }

    @Test
    fun `GrovsManager handleIntent updates eventsManager linkForFutureActions on success`() = runTest {
        grovsManager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED

        val intent = Intent().apply {
            data = Uri.parse("https://test.grovs.io/track")
        }

        val expectedDetails = DeeplinkDetails(
            link = "https://test.grovs.io/resolved-link",
            data = mapOf("key" to "value" as Object),
            tracking = null
        )

        coEvery { mockGrovsService.payloadWithLinkFor(any()) } returns LSResult.Success(expectedDetails)
        coEvery { mockEventsManager.setLinkToNewFutureActions(any(), any()) } just Runs

        grovsManager.handleIntent(intent, delayEvents = false)

        coVerify { mockEventsManager.setLinkToNewFutureActions("https://test.grovs.io/resolved-link", delayEvents = false) }
    }

    // ==================== Payment Events Tests ====================

    // PURCHASE_EVENT_DISABLED: @Test
    // PURCHASE_EVENT_DISABLED: fun `GrovsManager logInAppPurchase delegates to eventsManager`() = runTest {
    // PURCHASE_EVENT_DISABLED:     val originalJson = """{"orderId": "test123", "productId": "premium"}"""
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     coEvery { mockEventsManager.logInAppPurchase(any()) } just Runs
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     grovsManager.logInAppPurchase(originalJson)
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     coVerify { mockEventsManager.logInAppPurchase(originalJson) }
    // PURCHASE_EVENT_DISABLED: }

    // PURCHASE_EVENT_DISABLED: @Test
    // PURCHASE_EVENT_DISABLED: fun `GrovsManager logCustomPurchase delegates to eventsManager with correct parameters`() = runTest {
    // PURCHASE_EVENT_DISABLED:     coEvery {
    // PURCHASE_EVENT_DISABLED:         mockEventsManager.logCustomPurchase(any(), any(), any(), any(), any())
    // PURCHASE_EVENT_DISABLED:     } just Runs
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     grovsManager.logCustomPurchase(
    // PURCHASE_EVENT_DISABLED:         type = PaymentEventType.BUY,
    // PURCHASE_EVENT_DISABLED:         priceInCents = 999,
    // PURCHASE_EVENT_DISABLED:         currency = "USD",
    // PURCHASE_EVENT_DISABLED:         productId = "premium_feature"
    // PURCHASE_EVENT_DISABLED:     )
    // PURCHASE_EVENT_DISABLED:
    // PURCHASE_EVENT_DISABLED:     coVerify {
    // PURCHASE_EVENT_DISABLED:         mockEventsManager.logCustomPurchase(
    // PURCHASE_EVENT_DISABLED:             type = PaymentEventType.BUY,
    // PURCHASE_EVENT_DISABLED:             priceInCents = 999,
    // PURCHASE_EVENT_DISABLED:             currency = "USD",
    // PURCHASE_EVENT_DISABLED:             productId = "premium_feature",
    // PURCHASE_EVENT_DISABLED:             startDate = any()
    // PURCHASE_EVENT_DISABLED:         )
    // PURCHASE_EVENT_DISABLED:     }
    // PURCHASE_EVENT_DISABLED: }

    // ==================== Edge Cases Tests ====================

    @Test
    fun `GrovsManager identifier property can be set to null after being set`() {
        grovsContext.identifier = "existing-user"
        assertEqualsWithContext(
            "existing-user",
            grovsManager.identifier,
            "identifier",
            "after setting grovsContext.identifier"
        )

        grovsManager.identifier = null

        assertNullWithContext(
            grovsManager.identifier,
            "identifier",
            "after setting grovsManager.identifier=null"
        )
        assertNullWithContext(
            grovsContext.identifier,
            "grovsContext.identifier",
            "after setting grovsManager.identifier=null"
        )
    }

    @Test
    fun `GrovsManager handleIntent returns null when DeeplinkDetails has null link and null data`() = runTest {
        grovsManager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED

        val intent = Intent()

        val emptyDetails = DeeplinkDetails(
            link = null,
            data = null,
            tracking = null
        )

        coEvery { mockGrovsService.payloadFor(any()) } returns LSResult.Success(emptyDetails)
        coEvery { mockEventsManager.setLinkToNewFutureActions(any(), any()) } just Runs

        val result = grovsManager.handleIntent(intent, delayEvents = false)

        assertNullWithContext(
            result,
            "handleIntent result",
            "after handleIntent() when DeeplinkDetails has null link and data"
        )
    }
}
