package io.grovs

/**
 * Follows in-app messages. Every method runs on the main thread and has a default, so override only
 * what you need. Java apps extend [GrovsInAppListenerAdapter] instead. The SDK holds the listener
 * strongly until it is replaced or cleared with `Grovs.setInAppListener(null)`.
 */
interface GrovsInAppListener {
    /** Return false to stop the message. It gains no view, and nothing else shows for that event. */
    fun shouldShowInAppMessage(campaignId: String): Boolean = true

    /** The message is on screen and its view was counted. */
    fun onInAppMessageShown(campaignId: String) {}

    /** [buttonId] is null for a tap on the message itself; [payload] is set only for a key_value action. */
    fun onInAppMessageClicked(campaignId: String, buttonId: String?, payload: Map<String, Any?>?) {}

    /** [reason]: close_button, backdrop, swipe, app or orientation. */
    fun onInAppMessageDismissed(campaignId: String, reason: String) {}

    /** [value] is an Int for stars, the option id for a choice. Called instead of the dismissal. */
    fun onInAppMessageAnswered(campaignId: String, value: Any, comment: String?) {}

    /** The message could not be drawn. It gains no view and is tried again at its next trigger. */
    fun onInAppMessageFailed(campaignId: String, message: String) {}
}

/** For Java: Kotlin interface defaults are not Java defaults on this SDK's JVM target. */
open class GrovsInAppListenerAdapter : GrovsInAppListener
