package io.grovs.inapp

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.grovs.inapp.model.InAppOrientation
import io.grovs.inapp.model.InAppPushPermission
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.TimeZone

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class InAppDeviceFactsTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val zone = TimeZone.getDefault()

    @After
    fun tearDown() {
        TimeZone.setDefault(zone)
    }

    @Test
    fun `the language is the primary subtag, lowercased, with modern codes`() {
        assertEquals("pt", InAppDeviceFacts.language("pt-BR"))
        assertEquals("en", InAppDeviceFacts.language("EN_us"))
        assertEquals("he", InAppDeviceFacts.language(Locale("iw", "IL").toLanguageTag()))
        assertEquals("", InAppDeviceFacts.language(null))
    }

    @Test
    fun `reads the android facts and the user`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kathmandu"))
        val facts = InAppDeviceFacts(context, attributes = { mapOf("plan" to "pro", "age" to 31) }, activity = { null },
            pushPermission = { InAppPushPermission.DENIED }, unsupportedActions = { listOf("request_app_rating") })
        val device = facts.read()
        assertEquals("android", device.platform)
        assertEquals(android.os.Build.VERSION.RELEASE, device.osVersion)
        assertEquals(345, device.utcOffsetMinutes)
        assertEquals("pro", device.user.get("plan").asString)
        assertEquals(31, device.user.get("age").asInt)
        assertEquals(InAppPushPermission.DENIED, device.pushPermission)
        assertEquals(listOf("request_app_rating"), device.unsupportedActions)
        assertEquals(InAppOrientation.PORTRAIT, device.orientation)
    }

    @Test
    @Config(qualifiers = "land")
    fun `a landscape activity reads as landscape`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val facts = InAppDeviceFacts(context, attributes = { null }, activity = { activity })
        assertEquals(InAppOrientation.LANDSCAPE, facts.read().orientation)
        assertEquals(0, facts.read().user.size())
    }

    @Test
    fun `a host map that contains itself reads as no user`() {
        val looped = HashMap<String, Any>()
        looped["plan"] = "pro"
        looped["self"] = looped
        val facts = InAppDeviceFacts(context, attributes = { looped }, activity = { null })
        assertEquals(0, facts.read().user.size())
    }

    @Test
    fun `a host map changed while it is read reads as no user`() {
        val changing = object : AbstractMap<String, Any>() {
            override val entries: Set<Map.Entry<String, Any>> get() = throw ConcurrentModificationException()
        }
        val facts = InAppDeviceFacts(context, attributes = { changing }, activity = { null })
        assertEquals(0, facts.read().user.size())
    }

    @Test
    fun `notifications switched off below API 33 read as denied`() {
        assertEquals(InAppPushPermission.GRANTED, InAppDeviceFacts.notificationsPermission(context))
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        assertEquals(InAppPushPermission.DENIED, InAppDeviceFacts.notificationsPermission(context))
    }
}
