package io.grovs.e2e.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Bundle
import io.grovs.GrovsMessaging
import io.grovs.e2e.contract.ContractTestBase
import io.grovs.e2e.contract.PushPayloads
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
internal class PushForegroundDisplayE2ETest : ContractTestBase() {

    private val notifications get() = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private fun posted(): List<Notification> = shadowOf(notifications).allNotifications

    @After
    fun restoreDisplay() { GrovsMessaging.showForegroundNotifications = true }

    private fun installLauncherActivity() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(application.packageName)
        val resolved = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = application.packageName
                name = "${application.packageName}.MainActivity"
                applicationInfo = application.applicationInfo
            }
        }
        shadowOf(application.packageManager).addResolveInfoForIntent(launcher, resolved)
    }

    private fun drawableId(name: String) = application.resources.getIdentifier(name, "drawable", application.packageName)

    private fun setMetaData(key: String, value: Any) {
        val info = shadowOf(application.packageManager).getInternalMutablePackageInfo(application.packageName)!!.applicationInfo!!
        val bundle = (info.metaData ?: Bundle()).apply {
            when (value) { is Int -> putInt(key, value); else -> putString(key, value.toString()) }
        }
        info.metaData = bundle
        application.applicationInfo!!.metaData = bundle
    }

    private fun receive(message: com.google.firebase.messaging.RemoteMessage = PushPayloads.remoteMessage()): Boolean {
        val handled = GrovsMessaging.handleMessage(application, message)
        settle(10_000)
        return handled
    }

    @Test
    fun `P29 a foreground Grovs push with a notification is posted by default`() {
        authenticated()
        assertTrue(receive())

        val shown = posted().single()
        assertEquals("Spring sale", shown.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("20% off today", shown.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun `P29 with foreground display off nothing is posted and the message is still handled`() {
        GrovsMessaging.showForegroundNotifications = false
        authenticated()
        assertTrue(receive())
        assertTrue(posted().isEmpty())

        GrovsMessaging.showForegroundNotifications = true
        assertTrue(receive(PushPayloads.remoteMessage(messageId = "0:control")))
        assertEquals(1, posted().size)
    }

    @Test
    fun `P30 a non-Grovs message is not handled and posts nothing`() {
        authenticated()
        assertFalse(receive(PushPayloads.remoteMessage(data = mapOf("promo" to "x"))))
        assertTrue(posted().isEmpty())

        assertTrue(receive(PushPayloads.remoteMessage(messageId = "0:control")))
        assertEquals(1, posted().size)
    }

    @Test
    @Config(sdk = [33])
    fun `P31 the FCM default channel is used when the app created it`() {
        notifications.createNotificationChannel(NotificationChannel("app_default", "App", NotificationManager.IMPORTANCE_DEFAULT))
        setMetaData("com.google.firebase.messaging.default_notification_channel_id", "app_default")
        authenticated()
        receive()

        assertEquals("app_default", posted().single().channelId)
    }

    @Test
    @Config(sdk = [33])
    fun `P31 without an app channel the GrovsChannel is used`() {
        setMetaData("com.google.firebase.messaging.default_notification_channel_id", "never_created")
        authenticated()
        receive()

        assertEquals("GrovsChannel", posted().single().channelId)
        assertTrue(notifications.getNotificationChannel("GrovsChannel") != null)
    }

    @Test
    fun `P32 the Grovs small icon meta-data wins over the FCM default icon`() {
        setMetaData("io.grovs.NotificationIconSmall", "grovs_in_app_star")
        setMetaData("com.google.firebase.messaging.default_notification_icon", android.R.drawable.ic_dialog_info)
        authenticated()
        receive()

        assertNotEquals(0, drawableId("grovs_in_app_star"))
        assertEquals(drawableId("grovs_in_app_star"), posted().single().smallIcon.resId)
    }

    @Test
    fun `P32 the FCM default icon is used when there is no Grovs icon`() {
        setMetaData("com.google.firebase.messaging.default_notification_icon", android.R.drawable.ic_dialog_info)
        authenticated()
        receive()

        assertEquals(android.R.drawable.ic_dialog_info, posted().single().smallIcon.resId)
    }

    @Test
    fun `P32 the SDK default icon is used when the app sets none`() {
        authenticated()
        receive()

        assertNotEquals(0, posted().single().smallIcon.resId)
    }

    @Test
    fun `P33 an http image is refused and the notification is posted without it`() {
        authenticated()
        receive(PushPayloads.remoteMessage(image = backend.baseUrl + "image.png"))

        val shown = posted().single()
        assertNull(shown.extras.get(Notification.EXTRA_PICTURE))
        assertEquals("no download of a non-https image", 0, backend.seen.count { it.request.path!!.endsWith("image.png") })
    }

    @Test
    fun `P33 an unreachable https image still posts the notification`() {
        authenticated()
        receive(PushPayloads.remoteMessage(image = "https://127.0.0.1:1/unreachable.png"))

        assertNull(posted().single().extras.get(Notification.EXTRA_PICTURE))
    }

    @Test
    fun `P34 tapping the posted notification opens the app and reports the open`() {
        installLauncherActivity()
        authenticated()
        receive(PushPayloads.remoteMessage(data = PushPayloads.data(link = null)))
        val tap = shadowOf(posted().single().contentIntent).savedIntent

        foreground(tap)
        settle(5_000)

        pumpUntil("the open and its delivery") { pushOpens() >= 1 && deepLinks.delivered.isNotEmpty() }
        assertEquals(application.packageName, tap.component?.packageName)
        assertEquals(1, pushOpens())
        assertEquals("spring26", deepLinks.delivered.single().data!!["promo"])
    }

    @Test
    fun `P35 a redelivered push replaces its notification`() {
        authenticated()
        receive(PushPayloads.remoteMessage(messageId = "0:same"))
        receive(PushPayloads.remoteMessage(title = "Autumn sale", messageId = "0:same"))

        assertEquals(1, posted().size)
        assertEquals("Autumn sale", posted().single().extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun `P36 nothing is posted while notifications are disabled`() {
        shadowOf(notifications).setNotificationsEnabled(false)
        authenticated()
        receive()

        assertTrue(posted().isEmpty())

        shadowOf(notifications).setNotificationsEnabled(true)
        receive(PushPayloads.remoteMessage(messageId = "0:control"))
        assertEquals(1, posted().size)
    }

    @Test
    @Config(sdk = [23])
    fun `P37 the default sound below API 26 uses the system default sound`() {
        authenticated()
        receive(PushPayloads.remoteMessage(sound = "default"))

        val shown = posted().single()
        val usesDefault = shown.defaults and Notification.DEFAULT_SOUND != 0 ||
            shown.sound == android.provider.Settings.System.DEFAULT_NOTIFICATION_URI
        assertTrue(usesDefault)
    }

    @Test
    @Config(sdk = [23])
    fun `P37 a named sound with no raw resource still posts without a custom sound`() {
        authenticated()
        receive(PushPayloads.remoteMessage(sound = "no_such_sound"))

        val shown = posted().single()
        assertFalse(shown.sound?.toString()?.contains("no_such_sound") == true)
    }
}
