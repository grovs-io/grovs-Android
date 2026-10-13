package io.grovs

/**
 * Entry points for an app that has its own FirebaseMessagingService. Only one service receives
 * FCM messages, so forward to these from yours, or extend io.grovs.handlers.MessagingService.
 */
object GrovsMessaging {

    /** Call from FirebaseMessagingService.onNewToken. Same as setting Grovs.pushToken. */
    @JvmStatic
    fun onNewToken(token: String) {
        Grovs.pushToken = token
    }
}
