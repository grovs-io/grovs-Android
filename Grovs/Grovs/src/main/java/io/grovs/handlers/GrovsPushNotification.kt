package io.grovs.handlers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.grovs.R
import kotlin.random.Random

internal const val GROVS_NOTIFICATION_CHANNEL_ID = "GrovsChannel"

/**
 * Posts a Grovs push as a local notification. Kept off the Firebase service so it can run
 * with a plain [Context] under test on every API level the SDK supports (minSdk 21).
 */
internal fun showGrovsPushNotification(context: Context, title: String?, body: String?, smallIcon: Int) {
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // Notification channels only exist from API 26. Below that the class is missing at
    // runtime and touching it crashes the host app's messaging service.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            GROVS_NOTIFICATION_CHANNEL_ID,
            "Grovs Channel",
            NotificationManager.IMPORTANCE_HIGH,
        )
        channel.description = "Channel for Grovs messages"
        channel.enableLights(true)
        channel.lightColor = ContextCompat.getColor(context, R.color.grovs_push_notification_icon_tint)
        channel.enableVibration(true)
        notificationManager.createNotificationChannel(channel)
    }

    val notification = NotificationCompat.Builder(context, GROVS_NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(smallIcon)
        .setContentTitle(title)
        .setContentText(body)
        .setAutoCancel(true)
        // Pre-26 devices have no channel importance, so this is what makes the
        // notification heads-up there. On 26+ the channel importance wins.
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()

    val notificationId = Random.nextInt(1000, Int.MAX_VALUE)
    notificationManager.notify(notificationId, notification)
}
