package io.grovs

import android.app.Application
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.grovs.e2e.E2ETestUtils
import io.grovs.e2e.TestActivity
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** The inbox is gone; its public calls must stay callable, answer on main once, and show nothing. */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LegacyMessagesApiTest {

    private lateinit var application: Application
    private var server: MockWebServer? = null
    private val paths = CopyOnWriteArrayList<String>()
    private val mainLooper: Looper get() = Looper.getMainLooper()

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        E2ETestUtils.resetGrovsSingleton()
        E2ETestUtils.setupTestApplication(application)
        E2ETestUtils.setupMockGlInfo()
        E2ETestUtils.setupMockUserAgent("Grovs SDK legacy messages tests")
    }

    @After
    fun tearDown() {
        server?.let(E2ETestUtils::cleanupMockWebServer)
        E2ETestUtils.resetGrovsSingleton()
    }

    @Test
    fun `before configure the inbox calls answer once on main`() = assertHarmless()

    @Test
    fun `after authentication the inbox calls answer once on main`() {
        configure()
        assertHarmless()
    }

    @Test
    fun `with the SDK disabled the inbox calls still answer once on main`() {
        configure()
        Grovs.setSDK(false)
        assertHarmless()
    }

    @Test
    fun `calls from a worker thread are answered on main`() {
        val answers = CopyOnWriteArrayList<Pair<String, Looper?>>()
        val worker = Thread {
            Grovs.displayMessagesFragment { answers += "dismissed" to Looper.myLooper() }
            Grovs.numberOfUnreadMessages { answers += "count $it" to Looper.myLooper() }
        }
        worker.start()
        worker.join(1_000)
        assertTrue("nothing may run on the worker", answers.isEmpty())

        shadowOf(mainLooper).idle()

        assertEquals(listOf("dismissed" to mainLooper, "count 0" to mainLooper), answers)
    }

    @Test
    fun `no fragment is shown over the current activity`() {
        configure()
        val activity = Robolectric.buildActivity(TestActivity::class.java).setup().get()
        val before = activity.supportFragmentManager.fragments.toList()

        Grovs.displayMessagesFragment { }
        shadowOf(mainLooper).idle()

        assertEquals(before, activity.supportFragmentManager.fragments.toList())
    }

    @Test
    fun `a destroyed lifecycle owner gets nothing`() {
        val owner = FakeOwner().apply { moveTo(Lifecycle.State.RESUMED); moveTo(Lifecycle.State.DESTROYED) }
        val counts = CopyOnWriteArrayList<Int?>()

        Grovs.numberOfUnreadMessages(owner) { counts += it }
        shadowOf(mainLooper).idle()

        assertTrue(counts.isEmpty())
    }

    @Test
    fun `an owner destroyed before the answer runs gets nothing`() {
        val owner = FakeOwner().apply { moveTo(Lifecycle.State.RESUMED) }
        val counts = CopyOnWriteArrayList<Int?>()

        Grovs.numberOfUnreadMessages(owner) { counts += it }
        owner.moveTo(Lifecycle.State.DESTROYED)
        shadowOf(mainLooper).idle()

        assertTrue(counts.isEmpty())
    }

    @Test
    fun `a live lifecycle owner gets 0`() {
        val owner = FakeOwner().apply { moveTo(Lifecycle.State.RESUMED) }
        val counts = CopyOnWriteArrayList<Int?>()

        Grovs.numberOfUnreadMessages(owner) { counts += it }
        shadowOf(mainLooper).idle()

        assertEquals(listOf<Int?>(0), counts)
    }

    @Test
    fun `the automatic notifications listener is never called`() {
        val calls = CopyOnWriteArrayList<Boolean>()
        Grovs.setOnAutomaticNotificationsListener { calls += it }

        configure()
        Robolectric.buildActivity(TestActivity::class.java).setup()
        shadowOf(mainLooper).idle()

        assertTrue(calls.isEmpty())
    }

    private fun assertHarmless() {
        val dismissed = CopyOnWriteArrayList<Looper?>()
        val counts = CopyOnWriteArrayList<Pair<Int?, Looper?>>()

        Grovs.displayMessagesFragment { dismissed += Looper.myLooper() }
        Grovs.displayMessagesFragment(null)
        Grovs.numberOfUnreadMessages { counts += it to Looper.myLooper() }
        assertTrue("callbacks must never run inline", dismissed.isEmpty() && counts.isEmpty())

        shadowOf(mainLooper).idle()
        shadowOf(mainLooper).idle()

        assertEquals("dismissed exactly once, on main", listOf(mainLooper), dismissed.toList())
        assertEquals("one count of 0, on main", listOf<Pair<Int?, Looper?>>(0 to mainLooper), counts.toList())
        assertEquals(0, runBlocking { Grovs.numberOfUnreadMessages() })
    }

    private fun configure() {
        val server = E2ETestUtils.createMockWebServer().also { this.server = it }
        E2ETestUtils.setUrlDispatcher(server, linkedMapOf(
            "authenticate" to json("""{"linksquared":"test-grovs-id-123","uri_scheme":"testapp"}"""),
            "device_for_vendor_id" to json("""{"last_seen":null}"""),
        ))
        val answers = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path.orEmpty()
                return answers.dispatch(request)
            }
        }
        E2ETestUtils.configureAndWaitForAuthOnly(application, baseURL = server.url("/").toString())
        E2ETestUtils.assertAuthenticationCompleted()
    }

    private fun json(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private class FakeOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun moveTo(state: Lifecycle.State) { registry.currentState = state }
    }

    /** Never run: proves every deprecated call site from 3.0.x still compiles. */
    @Suppress("unused")
    private suspend fun callSitesThatMustKeepCompiling(owner: LifecycleOwner) {
        Grovs.setOnAutomaticNotificationsListener { _: Boolean -> }
        Grovs.setOnAutomaticNotificationsListener(GrovsNotificationsListener { })
        Grovs.displayMessagesFragment { }
        Grovs.displayMessagesFragment(null)
        val count: Int? = Grovs.numberOfUnreadMessages()
        Grovs.numberOfUnreadMessages { _: Int? -> }
        Grovs.numberOfUnreadMessages(owner) { }
        Grovs.numberOfUnreadMessages(lifecycleOwner = owner, onResult = null)
        Grovs.numberOfUnreadMessages(onResult = { })
        count.hashCode()
    }
}
