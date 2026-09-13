package io.grovs.handlers

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import io.grovs.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
