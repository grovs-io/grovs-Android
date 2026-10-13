package io.grovs

import android.content.Context
import android.graphics.Bitmap
import com.google.firebase.messaging.RemoteMessage
import io.grovs.handlers.GrovsPushContent
import io.grovs.handlers.GrovsPushPayload
import io.grovs.handlers.PushImageLoader
import io.grovs.handlers.showGrovsPushNotification

/**
 * Entry points for an app that has its own FirebaseMessagingService. Only one service receives
 * FCM messages, so forward to these from yours, or extend io.grovs.handlers.MessagingService.
 */
object GrovsMessaging {

    /** Show Grovs pushes that arrive while the app is open. Background pushes are shown by FCM. */
    @Volatile
    @JvmStatic
    var showForegroundNotifications: Boolean = true

    /**
     * Call from FirebaseMessagingService.onMessageReceived. Shows a Grovs push and returns true;
     * returns false for any other message, which is left to the app.
     */
    @JvmStatic
    fun handleMessage(context: Context, message: RemoteMessage): Boolean {
        val content = message.notification?.let { GrovsPushContent(it.title, it.body, it.imageUrl?.toString(), it.sound) }
        return handlePush(context, message.data, message.messageId, content)
    }

    /** Call from FirebaseMessagingService.onNewToken. Same as setting Grovs.pushToken. */
    @JvmStatic
    fun onNewToken(token: String) {
        Grovs.pushToken = token
    }

    internal fun handlePush(
        context: Context,
        data: Map<String, String>,
        messageId: String?,
        content: GrovsPushContent?,
        loadImage: (String?) -> Bitmap? = PushImageLoader::load,
    ): Boolean {
        val push = GrovsPushPayload.from(data, messageId) ?: return false
        if (content != null && showForegroundNotifications) {
            showGrovsPushNotification(context, push, content, loadImage(content.imageUrl ?: push.image))
        }
        return true
    }
}
