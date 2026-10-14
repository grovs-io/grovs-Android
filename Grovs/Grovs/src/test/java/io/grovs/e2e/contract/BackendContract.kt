package io.grovs.e2e.contract

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest

/** What the backend accepts and answers, written down apart from the SDK. */
internal object BackendContract {
    /** SDK routes, as paths after /api/v1/sdk/. */
    val SDK_ROUTES = setOf(
        "authenticate", "device_for_vendor_id", "data_for_device", "data_for_device_and_url",
        "data_for_device_and_path", "link_details", "clipboard_status", "create_link", "smart_banners",
        "event", "event/custom", "events/batch", "notifications_for_device", "push_open",
        "number_of_unread_notifications", "mark_notification_as_read",
        "notifications_to_display_automatically", "screen_aliases", "visitor_attributes",
        "add_payment_event", "in_app/sync",
    )

    /** The one route with a path parameter. */
    private val BANNER_LINK = Regex("""smart_banners/[^/]+/link""")

    fun isSdkRoute(path: String): Boolean = path in SDK_ROUTES || BANNER_LINK.matches(path)

    /** Old inbox routes, still answering 200. */
    val INBOX_ROUTES = setOf(
        "notifications_for_device", "number_of_unread_notifications",
        "mark_notification_as_read", "notifications_to_display_automatically",
    )

    /** Routes that do not check the LINKSQUARED device header. */
    val DEVICE_CHECK_EXEMPT = setOf("authenticate", "device_for_vendor_id")

    /** Event names the server reserves for itself. */
    val SERVER_RESERVED = setOf(
        "app_open", "view", "open", "install", "reinstall", "time_spent", "reactivation", "user_referred", "push_open",
    )

    /** Allowed values of the `event` field. */
    val SYSTEM_EVENTS = setOf("app_open", "view", "open", "install", "reinstall", "time_spent", "reactivation", "user_referred")

    /** MESSAGE_EVENT_NAMES minus push_open; refused unless InAppEventCheck accepts them. */
    val MESSAGE_EVENTS = setOf(
        "in_app_viewed", "in_app_clicked", "in_app_dismissed", "in_app_answered", "in_app_commented",
        "in_app_held", "push_delivered", "push_held", "journey_entered", "journey_held", "journey_path",
    )

    /** In-app events a device may send. */
    val DEVICE_IN_APP_EVENTS = setOf("in_app_viewed", "in_app_clicked", "in_app_dismissed", "in_app_answered", "in_app_commented")

    const val MAX_BATCH = 50

    /** One report is tried 4 times with backoff before it is held. */
    const val IN_REQUEST_ATTEMPTS = 4

    /** An open is dropped after 3 reports. */
    const val REPORT_ATTEMPTS = 3

    /** Stamps the backend answers when in-app is switched off. */
    const val OFF_STAMPS = """{"campaigns_changed_at":"off","user_changed_at":"off"}"""

    const val PUSH_OPEN_RECORDED = """{"message":"Recorded"}"""
    const val PUSH_OPEN_ALREADY = """{"message":"Already recorded"}"""

    /** Header names match ignoring case, with `-` and `_` treated alike. */
    fun header(request: RecordedRequest, name: String): String? {
        fun norm(s: String) = s.uppercase().replace('_', '-')
        return request.headers.firstOrNull { norm(it.first) == norm(name) }?.second
    }

    fun json(code: Int, body: String): MockResponse =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
}
