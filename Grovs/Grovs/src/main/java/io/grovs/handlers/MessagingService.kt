package io.grovs.handlers

import android.content.pm.PackageManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.grovs.GrovsMessaging
import io.grovs.R
import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel

open class MessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        DebugLogger.instance.log(LogLevel.INFO, "Push notification handled by grovs FirebaseMessagingService service.")
        if (handleGrovsNotification(message)) {
            DebugLogger.instance.log(LogLevel.INFO, "Push notification if from grovs -> handled.")
        } else {
            DebugLogger.instance.log(LogLevel.INFO, "Push notification if NOT from grovs -> ignored.")
        }
    }

    override fun onNewToken(token: String) {
        GrovsMessaging.onNewToken(token)
    }
}

fun FirebaseMessagingService.handleGrovsNotification(message: RemoteMessage): Boolean {
    if (GrovsPushPayload.from(message.data, message.messageId) == null) {
        return false
    }

    DebugLogger.instance.log(LogLevel.INFO, "Received push notification: ${message.notification} data: ${message.data} ")

    // Retrieve the drawable name from meta-data
    val applicationInfo = packageManager.getApplicationInfo(
        packageName,
        PackageManager.GET_META_DATA
    )
    val iconName = applicationInfo.metaData?.getString("io.grovs.NotificationIconSmall")
    // Get the drawable resource ID
    val iconResId = iconName?.let { resources.getIdentifier(it, "drawable", packageName) }

    showGrovsPushNotification(
        this,
        message.notification?.title,
        message.notification?.body,
        iconResId ?: R.drawable.ic_grovs_notification_default_small,
        message.data,
    )

    return true
}