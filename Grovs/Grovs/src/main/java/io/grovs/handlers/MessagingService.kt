package io.grovs.handlers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.grovs.R
import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel
import kotlin.random.Random

class MessagingService: FirebaseMessagingService() {

    override fun onCreate() {
        super.onCreate()

    }

    override fun onMessageReceived(message: RemoteMessage) {
        DebugLogger.instance.log(LogLevel.INFO, "Push notification handled by grovs FirebaseMessagingService service.")
        if (handleGrovsNotification(message)) {
            DebugLogger.instance.log(LogLevel.INFO, "Push notification if from grovs -> handled.")
        } else {
            DebugLogger.instance.log(LogLevel.INFO, "Push notification if NOT from grovs -> ignored.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()

    }

}

fun FirebaseMessagingService.handleGrovsNotification(message: RemoteMessage): Boolean {
    val data = message.data
    if (data["linksquared"] == null) {
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

    handleGrovsNotification(message.notification?.title, message.notification?.body, iconResId ?: R.drawable.ic_grovs_notification_default_small)

    return true
}

private fun FirebaseMessagingService.handleGrovsNotification(title: String?, body: String?, smallIcon: Int) {
    val channelId = "GrovsChannel"

    // Build the notification
    val notificationBuilder = NotificationCompat.Builder(this, channelId)
        .setSmallIcon(smallIcon)
        .setContentTitle(title)
        .setContentText(body)
        .setAutoCancel(true)

    // Send the notification
    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    val channel = NotificationChannel(channelId, "Grovs Channel", NotificationManager.IMPORTANCE_HIGH)
    channel.description = "Channel for Grovs messages"
    channel.enableLights(true)
    channel.lightColor = getColor(R.color.grovs_push_notification_icon_tint)
    channel.enableVibration(true)

    notificationManager.createNotificationChannel(channel)

    var notificationId = Random.nextInt(1000, Int.MAX_VALUE)
    notificationManager.notify(notificationId, notificationBuilder.build())
}