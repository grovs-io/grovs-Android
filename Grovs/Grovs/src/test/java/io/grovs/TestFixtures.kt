package io.grovs

import io.grovs.model.AppDetails
import io.grovs.model.CustomLinkRedirect
import io.grovs.model.DeeplinkDetails
import io.grovs.model.Event
import io.grovs.model.EventType
import io.grovs.model.GenerateLinkRequest
// PURCHASE_EVENT_DISABLED: import io.grovs.model.events.PaymentEvent
// PURCHASE_EVENT_DISABLED: import io.grovs.model.events.PaymentEventType
import io.grovs.utils.InstantCompat

/**
 * Shared test fixtures for creating common test objects.
 * Consolidates factory methods that were previously duplicated across test files.
 */
object TestFixtures {

    const val TEST_API_KEY = "test-api-key-123"
    const val TEST_GROVS_ID = "grovs_123"
    const val TEST_DEVICE_ID = "test-device-id"
    const val TEST_BUNDLE_ID = "io.grovs.test"
    const val TEST_URI_SCHEME = "testscheme"
    const val TEST_LINK = "https://test.link/abc123"

    /**
     * Creates a test AppDetails with sensible defaults.
     * All parameters can be overridden as needed.
     */
    fun createAppDetails(
        version: String = "1.0.0",
        build: String = "1",
        bundle: String = TEST_BUNDLE_ID,
        device: String = "Test Device",
        deviceID: String = TEST_DEVICE_ID,
        userAgent: String = "Test User Agent",
        screenWidth: String = "1080",
        screenHeight: String = "1920",
        timezone: String = "UTC",
        language: String = "en-US",
        webglVendor: String? = "Test Vendor",
        webglRenderer: String? = "Test Renderer"
    ) = AppDetails(
        version = version,
        build = build,
        bundle = bundle,
        device = device,
        deviceID = deviceID,
        userAgent = userAgent,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        timezone = timezone,
        language = language,
        webglVendor = webglVendor,
        webglRenderer = webglRenderer
    )

    /**
     * Creates a test GenerateLinkRequest with minimal required fields.
     */
    fun createGenerateLinkRequest(
        title: String? = "Test Title",
        subtitle: String? = null,
        imageUrl: String? = null,
        data: String? = null,
        tags: String? = null,
        iosCustomRedirect: CustomLinkRedirect? = null,
        androidCustomRedirect: CustomLinkRedirect? = null,
        desktopCustomRedirect: CustomLinkRedirect? = null,
        showPreviewIos: Boolean? = null,
        showPreviewAndroid: Boolean? = null,
        trackingCampaign: String? = null,
        trackingMedium: String? = null,
        trackingSource: String? = null
    ) = GenerateLinkRequest(
        title = title,
        subtitle = subtitle,
        imageUrl = imageUrl,
        data = data,
        tags = tags,
        iosCustomRedirect = iosCustomRedirect,
        androidCustomRedirect = androidCustomRedirect,
        desktopCustomRedirect = desktopCustomRedirect,
        showPreviewIos = showPreviewIos,
        showPreviewAndroid = showPreviewAndroid,
        trackingCampaign = trackingCampaign,
        trackingMedium = trackingMedium,
        trackingSource = trackingSource
    )

    /**
     * Creates a test Event.
     */
    fun createEvent(
        event: EventType = EventType.VIEW,
        createdAt: InstantCompat = InstantCompat.now(),
        link: String? = null,
        engagementTime: Int? = null
    ) = Event(
        event = event,
        createdAt = createdAt,
        link = link,
        engagementTime = engagementTime
    )

    // PURCHASE_EVENT_DISABLED: /**
    // PURCHASE_EVENT_DISABLED:  * Creates a test PaymentEvent.
    // PURCHASE_EVENT_DISABLED:  */
    // PURCHASE_EVENT_DISABLED: fun createPaymentEvent(
    // PURCHASE_EVENT_DISABLED:     eventType: PaymentEventType = PaymentEventType.BUY,
    // PURCHASE_EVENT_DISABLED:     priceCents: Long? = 999L,
    // PURCHASE_EVENT_DISABLED:     currency: String? = "USD",
    // PURCHASE_EVENT_DISABLED:     productId: String? = "test_product",
    // PURCHASE_EVENT_DISABLED:     date: InstantCompat? = InstantCompat.now()
    // PURCHASE_EVENT_DISABLED: ) = PaymentEvent(
    // PURCHASE_EVENT_DISABLED:     eventType = eventType,
    // PURCHASE_EVENT_DISABLED:     priceCents = priceCents,
    // PURCHASE_EVENT_DISABLED:     currency = currency,
    // PURCHASE_EVENT_DISABLED:     productId = productId,
    // PURCHASE_EVENT_DISABLED:     date = date
    // PURCHASE_EVENT_DISABLED: )

    /**
     * Creates a test DeeplinkDetails.
     */
    fun createDeeplinkDetails(
        link: String? = TEST_LINK,
        data: Map<String, Object>? = null,
        tracking: Map<String, Object>? = null
    ) = DeeplinkDetails(
        link = link,
        data = data,
        tracking = tracking
    )
}
