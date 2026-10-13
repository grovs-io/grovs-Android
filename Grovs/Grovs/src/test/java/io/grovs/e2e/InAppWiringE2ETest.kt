package io.grovs.e2e

import android.app.Application
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.grovs.Grovs
import io.grovs.handlers.GrovsManager
import io.grovs.inapp.FakeInAppPresenter
import io.grovs.inapp.InAppFileStore
import io.grovs.inapp.InAppFixtures
import io.grovs.inapp.InAppKept
import io.grovs.inapp.InAppSynced
import io.grovs.inapp.NoDrawPresenter
import io.grovs.inapp.RecordingInAppListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class InAppWiringE2ETest {

    private lateinit var server: MockWebServer
    private lateinit var application: Application
    private val syncs = CopyOnWriteArrayList<JsonObject>()
    private val batchEvents = CopyOnWriteArrayList<JsonObject>()
    @Volatile private var campaigns: List<JsonObject> = emptyList()
    @Volatile private var batchStamps = """{"campaigns_changed_at":"c1","user_changed_at":"u1"}"""
    private val presenter = FakeInAppPresenter()
    private val listener = RecordingInAppListener()

    @Before
    fun setUp() {
        E2ETestUtils.resetGrovsSingleton()
        E2ETestUtils.setupMockGlInfo()
        application = RuntimeEnvironment.getApplication()
        E2ETestUtils.setupTestApplication(application)
        E2ETestUtils.setupMockUserAgent("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36")
        application.noBackupFilesDir.resolve("grovs/in_app").deleteRecursively()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.contains("authenticate") ->
                        MockResponse().setBody("""{"linksquared":"grovs_123","uri_scheme":"testscheme"}""")
                    path.contains("in_app/sync") -> {
                        syncs.add(JsonParser.parseString(request.body.readUtf8()).asJsonObject)
                        MockResponse().setBody(syncAnswer())
                    }
                    path.contains("events/batch") -> {
                        val events = JsonParser.parseString(request.body.readUtf8()).asJsonObject.getAsJsonArray("events")
                        events.forEach { batchEvents.add(it.asJsonObject) }
                        MockResponse().setBody("""{"accepted":${events.size()},"rejected":0,"errors":[],"in_app":$batchStamps}""")
                    }
                    else -> MockResponse().setBody("{}")
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        E2ETestUtils.cleanupMockWebServer(server)
        E2ETestUtils.resetGrovsSingleton()
    }

    private fun syncAnswer(): String {
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(System.currentTimeMillis())
        return """{"campaigns_changed_at":"c1","user_changed_at":"u1","server_time":"$now","store_url":null,
            "first_seen_at":"2026-08-01T00:00:00Z","global_caps":{"session":10,"day":10},
            "campaigns":[${campaigns.joinToString(",")}],"test_messages":[]}"""
    }

    private fun pumpUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("condition not met within $timeoutMs ms")
    }

    private fun store() = InAppFileStore(application.noBackupFilesDir, "test_test-key")

    /** Configures, authenticates, opens an activity and waits for the first in-app sync to land. */
    private fun launch(vararg campaigns: JsonObject, draw: Boolean = true) {
        this.campaigns = campaigns.toList()
        E2ETestUtils.setInAppPresenter(if (draw) presenter else NoDrawPresenter)
        Grovs.setInAppListener(listener)
        Grovs.configure(application, "test-key", useTestEnvironment = true, baseURL = server.url("/").toString())
        runBlocking { E2ETestUtils.getAuthenticationJob()?.join() }
        Robolectric.buildActivity(TestActivity::class.java).setup()
        pumpUntil { InAppSynced.load(store()) != null }
    }

    private fun manager() = E2ETestUtils.getGrovsManager() as GrovsManager

    @Test
    fun `syncs after authentication with the format and sdk version`() {
        launch(InAppFixtures.campaign())
        val body = syncs.first()
        assertEquals(1, body.get("format").asInt)
        assertTrue(body.get("sdk_version").asString.isNotEmpty())
        assertTrue(!body.has("campaigns_changed_at") || body.get("campaigns_changed_at").isJsonNull)
    }

    @Test
    fun `a tracked event shows the message and queues the view as an event with whole numbers`() {
        launch(InAppFixtures.campaign())
        Grovs.track("cart_viewed")
        pumpUntil { presenter.ids == listOf("c1") }
        assertEquals(listOf("c1"), listener.shown)

        E2ETestUtils.flushCustomEvents()
        pumpUntil { batchEvents.any { it.get("event_name")?.asString == "in_app_viewed" } }
        val viewed = batchEvents.first { it.get("event_name").asString == "in_app_viewed" }
        assertEquals(InAppKept.load(store())!!.state.views.single().id, viewed.get("event_id").asString)
        val properties = viewed.getAsJsonObject("properties")
        assertEquals("c1", properties.get("campaign_id").asString)
        assertEquals("1", properties.get("revision").toString())
        assertEquals("interstitial", properties.get("template").asString)
    }

    @Test
    fun `an app_open campaign shows once per session`() {
        launch(InAppFixtures.campaign("""{"triggers":[{"type":"app_open"}],"caps":{"session":null}}"""))
        pumpUntil { presenter.ids == listOf("c1") }
        presenter.finish(io.grovs.inapp.InAppOutcome.Dismissed("close_button"))
        manager().inApp.onForeground()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("c1"), presenter.ids)
    }

    @Test
    fun `a screen view triggers a screen campaign`() {
        launch(InAppFixtures.campaign("""{"triggers":[{"type":"screen_view","name":"pricing"}]}"""))
        Grovs.trackScreenView("Pricing")
        pumpUntil { presenter.ids == listOf("c1") }
    }

    @Test
    fun `new stamps in a batch response sync again`() {
        launch(InAppFixtures.campaign("""{"triggers":[{"type":"event","name":"never"}]}"""))
        val before = syncs.size
        batchStamps = """{"campaigns_changed_at":"c2","user_changed_at":"u1"}"""
        Grovs.track("something")
        E2ETestUtils.flushCustomEvents()
        pumpUntil { batchEvents.isNotEmpty() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        pumpUntil { syncs.size == before + 1 }
        assertEquals("c1", syncs.last().get("campaigns_changed_at").asString)
    }

    @Test
    fun `disabling the SDK closes the message and reports nothing`() {
        launch(InAppFixtures.campaign())
        Grovs.track("cart_viewed")
        pumpUntil { presenter.ids == listOf("c1") }
        Grovs.setSDK(false)
        assertEquals(1, presenter.dismissals)
        assertTrue(listener.dismissals.isEmpty())

        Grovs.track("cart_viewed")
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("c1"), presenter.ids)
    }

    @Test
    fun `configure again closes the message of the old configuration`() {
        launch(InAppFixtures.campaign())
        Grovs.track("cart_viewed")
        pumpUntil { presenter.ids == listOf("c1") }
        Grovs.configure(application, "test-key", useTestEnvironment = true, baseURL = server.url("/").toString())
        assertEquals(1, presenter.dismissals)
        assertTrue(listener.dismissals.isEmpty())
    }

    @Test
    fun `a host cannot track an in-app event name`() {
        launch(InAppFixtures.campaign("""{"triggers":[{"type":"event","name":"in_app_viewed"}]}"""))
        Grovs.track("in_app_viewed", mapOf("campaign_id" to "c1"))
        E2ETestUtils.flushCustomEvents()
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<String>(), presenter.ids)
        assertFalse(batchEvents.any { it.get("event_name")?.asString == "in_app_viewed" })
    }

    @Test
    fun `oversized properties never reach a filter`() {
        launch(InAppFixtures.campaign("""{"triggers":[{"type":"event","name":"cart_viewed","filters":[{"field":"plan","op":"eq","value":"pro"}]}]}"""))
        Grovs.track("cart_viewed", mapOf("plan" to "pro", "blob" to "x".repeat(9_000)))
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<String>(), presenter.ids)

        Grovs.track("cart_viewed", mapOf("plan" to "pro"))
        pumpUntil { presenter.ids == listOf("c1") }
    }

    @Test
    fun `with no presenter yet a due message reaches the failure callback`() {
        val failures = CopyOnWriteArrayList<String>()
        launch(InAppFixtures.campaign(), draw = false)
        Grovs.setInAppListener(object : io.grovs.GrovsInAppListener {
            override fun onInAppMessageFailed(campaignId: String, message: String) { failures.add(campaignId) }
        })
        Grovs.track("cart_viewed")
        pumpUntil { failures == listOf("c1") }
    }

    @Test
    fun `pause and excluded screens set before configure are kept`() {
        Grovs.pauseInAppMessages()
        Grovs.setInAppExcludedScreens(listOf("Checkout"))
        launch(InAppFixtures.campaign())
        Grovs.track("cart_viewed")
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<String>(), presenter.ids)
        Grovs.resumeInAppMessages()
        pumpUntil { presenter.ids == listOf("c1") }
    }
}
