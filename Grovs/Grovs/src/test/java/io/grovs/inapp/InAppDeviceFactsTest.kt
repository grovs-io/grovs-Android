package io.grovs.inapp

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.grovs.inapp.model.InAppOrientation
import io.grovs.inapp.model.InAppPushPermission
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
    fun `the user is converted again only when the attributes are replaced`() {
        var attributes: Map<String, Any> = mapOf("plan" to "pro")
        val facts = InAppDeviceFacts(context, attributes = { attributes }, activity = { null })
        val first = facts.read().user
        assertSame(first, facts.read().user)

        attributes = mapOf("plan" to "free")
        assertEquals("free", facts.read().user.get("plan").asString)
    }

    @Test
    fun `the notification permission is asked again only after a second`() {
        var now = 10_000L
        var asked = 0
        var answer = InAppPushPermission.NOT_ASKED
        val facts = InAppDeviceFacts(context, attributes = { null }, activity = { null },
            pushPermission = { asked++; answer }, now = { now })
        facts.read()
        answer = InAppPushPermission.GRANTED
        assertEquals(InAppPushPermission.NOT_ASKED, facts.read().pushPermission)
        assertEquals(1, asked)

        now += InAppDeviceFacts.PERMISSION_FRESH_MS
        assertEquals(InAppPushPermission.GRANTED, facts.read().pushPermission)
        assertEquals(2, asked)
    }

    @Test
    fun `notifications switched off below API 33 read as denied`() {
        assertEquals(InAppPushPermission.GRANTED, InAppDeviceFacts.notificationsPermission(context))
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        assertEquals(InAppPushPermission.DENIED, InAppDeviceFacts.notificationsPermission(context))
    }
}
