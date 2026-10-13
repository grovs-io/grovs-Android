package io.grovs.handlers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** API 23 has no channels, 26 is the first with them, 33 has the runtime notification permission. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 26, 33])
class GrovsPushNotificationTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val push = GrovsPushPayload.from(
        mapOf("grovs_campaign_id" to "abc", "grovs_link" to "https://app.grovs.link/promo", "promo" to "x"),
        messageId = "0:m1",
    )!!
    private val content = GrovsPushContent(title = "Title", body = "Body", imageUrl = null, sound = null)

    private fun posted(): Notification = shadowOf(manager).allNotifications.single()

    @Test
    fun `posts the notification on every supported api level`() {
        showGrovsPushNotification(context, push, content, image = null)

        assertEquals("Title", shadowOf(posted()).contentTitle)
        assertEquals("Body", shadowOf(posted()).contentText)
    }

    @Test
    fun `tapping opens the launcher with the push data and the message id`() {
        registerLauncherActivity()

        showGrovsPushNotification(context, push, content, image = null)

        val contentIntent = posted().contentIntent
        assertNotNull(contentIntent)
        val opened = shadowOf(contentIntent).savedIntent
        assertEquals(LAUNCHER_ACTIVITY, opened.component?.className)
        assertEquals("abc", opened.getStringExtra("grovs_campaign_id"))
        assertEquals("https://app.grovs.link/promo", opened.getStringExtra("grovs_link"))
        assertEquals("0:m1", opened.getStringExtra(GrovsPushPayload.MESSAGE_ID_EXTRA))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            assertTrue(shadowOf(contentIntent).flags and PendingIntent.FLAG_IMMUTABLE != 0)
        }
    }

    @Test
    fun `without a launcher activity the notification still posts`() {
        showGrovsPushNotification(context, push, content, image = null)
        assertNull(posted().contentIntent)
    }

    @Test
    fun `a redelivered message replaces its notification instead of stacking`() {
        showGrovsPushNotification(context, push, content, image = null)
        showGrovsPushNotification(context, push, content, image = null)
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun `an image is shown as a big picture`() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

        showGrovsPushNotification(context, push, content, image = bitmap)

        assertTrue(posted().extras.containsKey(Notification.EXTRA_PICTURE))
    }

    @Test
    fun `uses the app's FCM default channel when the app created it`() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannel(NotificationChannel("app_default", "App", NotificationManager.IMPORTANCE_DEFAULT))
        setMetaData(Bundle().apply {
            putString("com.google.firebase.messaging.default_notification_channel_id", "app_default")
        })

        showGrovsPushNotification(context, push, content, image = null)

        assertEquals("app_default", posted().channelId)
        assertNull(manager.getNotificationChannel(GROVS_NOTIFICATION_CHANNEL_ID))
    }

    @Test
    fun `falls back to the Grovs channel when the FCM default channel does not exist`() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        setMetaData(Bundle().apply {
            putString("com.google.firebase.messaging.default_notification_channel_id", "never_created")
        })

        showGrovsPushNotification(context, push, content, image = null)

        assertEquals(GROVS_NOTIFICATION_CHANNEL_ID, posted().channelId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(GROVS_NOTIFICATION_CHANNEL_ID).importance)
    }

    @Test
    fun `nothing is posted when notifications are disabled`() {
        // Below API 24 NotificationManagerCompat asks AppOps, which the shadow switch does not reach.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        shadowOf(manager).setNotificationsEnabled(false)

        showGrovsPushNotification(context, push, content, image = null)

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `the default sound is used below API 26`() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return
        showGrovsPushNotification(context, push, GrovsPushContent("T", "B", null, sound = "default"), image = null)
        @Suppress("DEPRECATION")
        assertNotNull(posted().sound)
    }

    private fun setMetaData(bundle: Bundle) {
        shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).applicationInfo!!.metaData = bundle
    }

    private fun registerLauncherActivity() {
        val component = ComponentName(context.packageName, LAUNCHER_ACTIVITY)
        val packageManager = shadowOf(context.packageManager)
        packageManager.addActivityIfNotPresent(component)
        packageManager.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    private companion object {
        const val LAUNCHER_ACTIVITY = "io.grovs.test.LauncherActivity"
    }
}
