package io.grovs.handlers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import io.grovs.R
import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel

internal const val GROVS_NOTIFICATION_CHANNEL_ID = "GrovsChannel"
internal const val GROVS_NOTIFICATION_TAG = "grovs"
private const val GROVS_SMALL_ICON = "io.grovs.NotificationIconSmall"
private const val FCM_DEFAULT_CHANNEL = "com.google.firebase.messaging.default_notification_channel_id"
private const val FCM_DEFAULT_ICON = "com.google.firebase.messaging.default_notification_icon"

/** The notification block of an FCM message. */
internal class GrovsPushContent(val title: String?, val body: String?, val imageUrl: String?, val sound: String?)

/**
 * Posts a Grovs push received in the foreground, matching what FCM shows in the background:
 * same channel rule, image as a big picture, tap opens the launcher with the data as extras.
 */
internal fun showGrovsPushNotification(context: Context, push: GrovsPushPayload, content: GrovsPushContent, image: Bitmap?) {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
        DebugLogger.instance.log(LogLevel.INFO, "Notifications are disabled - the Grovs push is not shown")
        return
    }
    val metaData = applicationMetaData(context)
    val notificationId = (push.messageId ?: push.campaignId).hashCode()
    val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) channelFor(context, metaData) else GROVS_NOTIFICATION_CHANNEL_ID

    val builder = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(smallIcon(context, metaData))
        .setContentTitle(content.title)
        .setContentText(content.body)
        .setAutoCancel(true)
        .setContentIntent(openAppIntent(context, notificationId, push))
        // Pre-26 devices have no channel importance, so this is what makes the
        // notification heads-up there. On 26+ the channel importance wins.
        .setPriority(NotificationCompat.PRIORITY_HIGH)
    image?.let {
        builder.setLargeIcon(it).setStyle(NotificationCompat.BigPictureStyle().bigPicture(it).bigLargeIcon(null as Bitmap?))
    }
    // From API 26 the channel decides the sound.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) soundUri(context, content.sound)?.let(builder::setSound)

    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.notify(GROVS_NOTIFICATION_TAG, notificationId, builder.build())
}

/** The app's FCM default channel when the app created it, as FCM does, else the Grovs channel. */
@RequiresApi(Build.VERSION_CODES.O)
private fun channelFor(context: Context, metaData: Bundle?): String {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    metaData?.getString(FCM_DEFAULT_CHANNEL)
        ?.takeIf { manager.getNotificationChannel(it) != null }
        ?.let { return it }

    val channel = NotificationChannel(
        GROVS_NOTIFICATION_CHANNEL_ID,
        context.getString(R.string.grovs_notification_channel_name),
        NotificationManager.IMPORTANCE_HIGH,
    )
    channel.description = "Channel for Grovs messages"
    channel.enableLights(true)
    channel.lightColor = ContextCompat.getColor(context, R.color.grovs_push_notification_icon_tint)
    channel.enableVibration(true)
    manager.createNotificationChannel(channel)
    return GROVS_NOTIFICATION_CHANNEL_ID
}

private fun smallIcon(context: Context, metaData: Bundle?): Int {
    val grovsIcon = metaData?.getString(GROVS_SMALL_ICON)
        ?.let { context.resources.getIdentifier(it, "drawable", context.packageName) }
        ?.takeIf { it != 0 }
    return grovsIcon
        ?: metaData?.getInt(FCM_DEFAULT_ICON, 0)?.takeIf { it != 0 }
        ?: R.drawable.ic_grovs_notification_default_small
}

private fun soundUri(context: Context, sound: String?): Uri? = when {
    sound.isNullOrEmpty() -> null
    sound == "default" -> Settings.System.DEFAULT_NOTIFICATION_URI
    context.resources.getIdentifier(sound, "raw", context.packageName) != 0 ->
        Uri.parse("android.resource://${context.packageName}/raw/$sound")
    else -> null
}

private fun applicationMetaData(context: Context): Bundle? = try {
    context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
} catch (e: PackageManager.NameNotFoundException) {
    null
}

/** Opens the app the way FCM does for a background push: launcher activity, data as extras. */
private fun openAppIntent(context: Context, requestCode: Int, push: GrovsPushPayload): PendingIntent? {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    push.data.forEach { (key, value) -> launch.putExtra(key, value) }
    push.messageId?.let { launch.putExtra(GrovsPushPayload.MESSAGE_ID_EXTRA, it) }
    return PendingIntentCompat.getActivity(
        context, requestCode, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT, false,
    )
}
