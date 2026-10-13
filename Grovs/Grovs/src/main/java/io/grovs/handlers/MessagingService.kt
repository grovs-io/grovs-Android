package io.grovs.handlers

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.grovs.GrovsMessaging
import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel

/** Declared by the library manifest. Extend it if your app needs its own messaging service. */
open class MessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        if (!GrovsMessaging.handleMessage(this, message)) {
            DebugLogger.instance.log(LogLevel.INFO, "Push notification is not from Grovs - ignored.")
        }
    }

    override fun onNewToken(token: String) {
        GrovsMessaging.onNewToken(token)
    }
}

@Deprecated(
    "Use GrovsMessaging.handleMessage",
    ReplaceWith("GrovsMessaging.handleMessage(this, message)", "io.grovs.GrovsMessaging"),
)
fun FirebaseMessagingService.handleGrovsNotification(message: RemoteMessage): Boolean =
    GrovsMessaging.handleMessage(this, message)
