package io.grovs

import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import com.google.firebase.messaging.RemoteMessage
import io.grovs.handlers.GrovsPushContent
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GrovsMessagingTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val grovsData = mapOf("grovs_campaign_id" to "c1", "grovs_image" to "https://cdn.grovs.io/i.png")
    private val content = GrovsPushContent("Title", "Body", imageUrl = null, sound = null)

    @After
    fun restore() {
        GrovsMessaging.showForegroundNotifications = true
    }

    @Test
    fun `a non-Grovs message returns false and posts nothing`() {
        assertFalse(GrovsMessaging.handlePush(context, mapOf("other" to "x"), "m", content) { null })
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `a data-only Grovs message is recognised and posts nothing`() {
        val message = RemoteMessage.Builder("sender@fcm.googleapis.com").setMessageId("m1").setData(grovsData).build()

        assertTrue(GrovsMessaging.handleMessage(context, message))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `a Grovs notification message is shown with the campaign image`() {
        var requested: String? = null

        assertTrue(GrovsMessaging.handlePush(context, grovsData, "m1", content) { url ->
            requested = url
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        })

        assertTrue(shadowOf(manager).allNotifications.size == 1)
        assertTrue(requested == "https://cdn.grovs.io/i.png")
    }

    @Test
    fun `receiving a foreground push is not an open`() {
        GrovsMessaging.handlePush(context, grovsData, "m1", content) { null }
        // Opens are only reported from a tap intent (PushOpenE2ETest); receiving touches no manager.
        assertTrue(Grovs.openedLinkDetails == null)
    }

    @Test
    fun `foreground display can be turned off`() {
        GrovsMessaging.showForegroundNotifications = false

        assertTrue(GrovsMessaging.handlePush(context, grovsData, "m1", content) { null })
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
