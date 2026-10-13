package io.grovs.handlers

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import io.grovs.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The SDK declares minSdk 21 but notification channels only exist from API 26. Running the
 * same test on an API level below 26 and on 26 itself proves the handler works on both sides
 * of that line instead of crashing the host app's Firebase service on older phones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 26])
class GrovsPushNotificationTest {

    @Test
    fun `posts the notification on every supported api level`() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        showGrovsPushNotification(context, "Title", "Body", R.drawable.ic_grovs_notification_default_small)

        val notifications = shadowOf(manager).allNotifications
        assertEquals(1, notifications.size)
        assertEquals("Title", shadowOf(notifications.single()).contentTitle)
        assertEquals("Body", shadowOf(notifications.single()).contentText)
    }

    @Test
    fun `creates the channel only where channels exist`() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        showGrovsPushNotification(context, "Title", "Body", R.drawable.ic_grovs_notification_default_small)

        // Below API 26 there is nothing to assert about channels. Reaching this line
        // without an exception is the whole point on those devices.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = manager.getNotificationChannel(GROVS_NOTIFICATION_CHANNEL_ID)
            assertNotNull(channel)
            assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        }
    }

    @Test
    fun `tapping the notification opens the app with the push data`() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        registerLauncherActivity(context)

        showGrovsPushNotification(
            context, "Title", "Body", R.drawable.ic_grovs_notification_default_small,
            mapOf("grovs_campaign_id" to "abc", "grovs_link" to "https://app.grovs.link/promo"),
        )

        val contentIntent = shadowOf(manager).allNotifications.single().contentIntent
        assertNotNull("the notification should open the app when tapped", contentIntent)
        val opened = shadowOf(contentIntent).savedIntent
        assertEquals(LAUNCHER_ACTIVITY, opened.component?.className)
        assertEquals("abc", opened.getStringExtra("grovs_campaign_id"))
        assertEquals("https://app.grovs.link/promo", opened.getStringExtra("grovs_link"))
    }

    @Test
    fun `without a launcher activity the notification still posts`() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        showGrovsPushNotification(
            context, "Title", "Body", R.drawable.ic_grovs_notification_default_small,
            mapOf("grovs_campaign_id" to "abc"),
        )

        val notification = shadowOf(manager).allNotifications.single()
        assertNull(notification.contentIntent)
    }

    private fun registerLauncherActivity(context: Context) {
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
