package io.grovs.inapp

import android.app.Activity
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.grovs.handlers.GrovsContext
import io.grovs.handlers.ICustomEventsManager
import io.grovs.inapp.InAppFixtures.campaign
import io.grovs.service.IGrovsService
import io.grovs.utils.LSResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class InAppHostTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var grovsContext: GrovsContext
    private lateinit var service: IGrovsService
    private lateinit var customEvents: ICustomEventsManager
    private lateinit var presenter: FakeInAppPresenter
    private lateinit var settings: InAppSettings
    private lateinit var listener: RecordingFailures
    private var authenticated = true
    private var activity: Activity? = null
    private var answer: JsonObject = JsonObject()
    private val storeExecutor = InAppQueuedStore.newExecutor()

    class RecordingFailures : io.grovs.GrovsInAppListener {
        val failed = mutableListOf<String>()
        override fun onInAppMessageFailed(campaignId: String, message: String) { failed.add(campaignId) }
    }

    @Before
    fun setUp() {
        grovsContext = GrovsContext(serialDispatcher = Dispatchers.Unconfined)
        grovsContext.markAuthenticated("grovs-id", grovsContext.consent.currentConfiguration)
        grovsContext.isForeground = true
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        service = mockk(relaxed = true)
        coEvery { service.inAppSync(any()) } answers { LSResult.Success(answer) }
        customEvents = mockk(relaxed = true)
        presenter = FakeInAppPresenter()
        listener = RecordingFailures()
        settings = InAppSettings().also { it.listener = listener }
        RuntimeEnvironment.getApplication().noBackupFilesDir.resolve("grovs/in_app").deleteRecursively()
    }

    private fun respond(vararg campaigns: JsonObject) {
        answer = InAppFixtures.json("""{"campaigns_changed_at":"c1","user_changed_at":"u1","server_time":"${InAppFixtures.iso(System.currentTimeMillis())}",
            "first_seen_at":null,"global_caps":{"session":10,"day":10},"campaigns":[${campaigns.joinToString(",")}]}""")
    }

    private fun host(
        draw: InAppPresenter = presenter,
        images: InAppImages = InAppImages(temp.root, loader = { _, done -> done(null) }, decodes = { true }),
    ) = InAppHost(
        context = RuntimeEnvironment.getApplication(),
        grovsContext = grovsContext,
        configuration = grovsContext.consent.currentConfiguration,
        service = service,
        customEvents = customEvents,
        settings = settings,
        activity = { activity },
        isAuthenticated = { authenticated },
        presenter = { draw },
        storeKey = "key",
        images = images,
        storeExecutor = storeExecutor,
    )

    private fun onDisk() = InAppFileStore(RuntimeEnvironment.getApplication().noBackupFilesDir, "key")

    private fun drainWrites() {
        if (storeExecutor.isShutdown) assertTrue(storeExecutor.awaitTermination(5, TimeUnit.SECONDS))
        else storeExecutor.submit {}.get(5, TimeUnit.SECONDS)
    }

    /** The store's thread first: start() reads the saved messages there before it runs on the main thread. */
    private fun idle() {
        if (!storeExecutor.isShutdown) storeExecutor.submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `start syncs and raises app_open once per session`() {
        respond(campaign("""{"triggers":[{"type":"app_open"}],"caps":{"session":null}}"""))
        val host = host()
        host.start()
        idle()
        assertEquals(listOf("c1"), presenter.ids)
        presenter.finish(InAppOutcome.Dismissed("close_button"))

        host.onForeground()
        idle()
        assertEquals(listOf("c1"), presenter.ids)
        coVerify(exactly = 2) { service.inAppSync(any()) }
    }

    @Test
    fun `an opening shows when its picture arrives on the download thread`() {
        respond(campaign("""{"triggers":[{"type":"app_open"}],"content":{"image":{"portrait":"https://cdn.example/welcome.png"}}}"""))
        var download: ((ByteArray?) -> Unit)? = null
        val host = host(images = InAppImages(temp.root, loader = { _, done -> download = done }, decodes = { true }))
        host.start()
        idle()
        assertEquals(emptyList<String>(), presenter.ids)

        Thread { download!!(byteArrayOf(1)) }.apply { start(); join() }
        idle()
        assertEquals(listOf("c1"), presenter.ids)
    }

    @Test
    fun `a stop before the saved messages are read keeps in-app stopped`() {
        respond(campaign("""{"triggers":[{"type":"app_open"}]}"""))
        val host = host()
        host.start()
        host.stop()
        idle()
        coVerify(exactly = 0) { service.inAppSync(any()) }
        assertEquals(emptyList<String>(), presenter.ids)
    }

    @Test
    fun `nothing starts before authentication`() {
        authenticated = false
        host().start()
        idle()
        coVerify(exactly = 0) { service.inAppSync(any()) }
    }

    @Test
    fun `nothing starts while the SDK is disabled`() {
        respond(campaign())
        val host = host()
        grovsContext.consent.revoke()
        host.start()
        idle()
        assertFalse(host.controller.isRunning)
        coVerify(exactly = 0) { service.inAppSync(any()) }
    }

    @Test
    fun `a tracked event from any thread shows the message and queues the view under its id`() {
        respond(campaign())
        val host = host()
        host.start()
        idle()
        Thread { host.onTracked("cart_viewed", mapOf("sku" to "A1")) }.apply { start(); join() }
        idle()
        assertEquals(listOf("c1"), presenter.ids)
        assertEquals("A1", presenter.shown.single().event.get("sku").asString)

        val id = slot<String>()
        val properties = slot<Map<String, Any>>()
        coVerify { customEvents.trackInApp(capture(id), "in_app_viewed", capture(properties)) }
        assertEquals("c1", properties.captured["campaign_id"])
        assertEquals(1L, properties.captured["revision"])
        drainWrites()
        assertEquals(InAppKept.load(onDisk())!!.state.views.single().id, id.captured)
    }

    @Test
    fun `a screen view is matched by its screen name`() {
        respond(campaign("""{"triggers":[{"type":"screen_view","name":"pricing"}]}"""))
        val host = host()
        host.start()
        idle()
        host.onTracked("screen_view", mapOf("screen_name" to "Pricing"))
        idle()
        assertEquals(listOf("c1"), presenter.ids)
    }

    @Test
    fun `a page attribute merges into the user without taking over from the backend`() {
        respond(campaign("""{"template":"html","content":{"html":"<p>Hi</p>","layout":"modal"}}"""))
        val host = host()
        host.start()
        idle()
        host.onTracked("cart_viewed", null)
        idle()
        val page = presenter.shown.single()
        page.onShown!!()
        page.onAttribute!!("plan", JsonPrimitive("pro"))
        assertEquals("pro", grovsContext.attributes!!["plan"])
        assertFalse("the backend's attributes may still arrive", grovsContext.hostSetAttributes)
        page.onAttribute!!("plan", com.google.gson.JsonNull.INSTANCE)
        assertFalse(grovsContext.attributes!!.containsKey("plan"))
    }

    @Test
    fun `close dismisses the message and nothing shows afterwards`() {
        respond(campaign("""{"caps":{"session":null}}"""))
        val host = host()
        host.start()
        idle()
        host.onTracked("cart_viewed", null)
        idle()
        host.close()
        idle()
        assertEquals(1, presenter.dismissals)
        host.onTracked("cart_viewed", null)
        host.start()
        idle()
        assertEquals(listOf("c1"), presenter.ids)
    }

    @Test
    fun `a sync answer that arrives after close is dropped`() {
        respond(campaign())
        val reply = CompletableDeferred<LSResult<JsonObject>>()
        coEvery { service.inAppSync(any()) } coAnswers { reply.await() }
        val host = host()
        host.start()
        idle()
        reply.complete(LSResult.Success(answer))
        Thread { host.close() }.apply { start(); join() }
        idle()
        drainWrites()
        assertNull(onDisk().read(InAppRecord.CAMPAIGNS))
    }

    @Test
    fun `close lets queued writes finish and refuses later ones`() {
        respond(campaign())
        val host = host()
        host.start()
        idle()
        host.close()
        idle()
        assertTrue(storeExecutor.awaitTermination(5, TimeUnit.SECONDS))
        assertTrue(InAppSynced.load(onDisk())!!.campaigns.isNotEmpty())
    }

    @Test
    fun `changed batch stamps sync again after the jitter`() {
        respond(campaign())
        val host = host()
        host.start()
        idle()
        Thread { host.onBatchStamps("c2", "u1") }.apply { start(); join() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        coVerify(exactly = 2) { service.inAppSync(any()) }
    }

    @Test
    fun `a message with nowhere to draw reaches the listener`() {
        respond(campaign())
        val host = host(draw = NoDrawPresenter)
        host.start()
        idle()
        host.onTracked("cart_viewed", null)
        idle()
        assertEquals(listOf("c1"), listener.failed)
    }

    @Test
    fun `a sync that throws is a failed sync and the next one still runs`() {
        respond(campaign())
        coEvery { service.inAppSync(any()) } throws IllegalStateException("boom") andThen LSResult.Success(answer)
        val host = host()
        host.start()
        idle()
        host.onForeground()
        idle()
        coVerify(exactly = 2) { service.inAppSync(any()) }
        assertTrue(host.controller.isRunning)
    }
}
