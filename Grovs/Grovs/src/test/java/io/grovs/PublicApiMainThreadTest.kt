package io.grovs

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import io.grovs.e2e.E2ETestUtils
import io.grovs.e2e.TestActivity
import io.grovs.handlers.GrovsContext
import io.grovs.model.events.PaymentEventType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Every public entry point that host apps call from Activity lifecycle methods must return
 * without waiting on the SDK's serial dispatcher and without doing slow work inline.
 *
 * The SDK's serial dispatcher is replaced with a [StalledDispatcher] that queues work and never
 * runs it. A call that only *hands off* work returns immediately; a call that *waits* for that
 * work hangs until the watchdog rescues it, which the assertions then report.
 *
 * The wall-clock budget is deliberately coarse. It is not a benchmark; it exists to catch
 * synchronous disk, network or WebView work sneaking into a lifecycle-path call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PublicApiMainThreadTest {

    private lateinit var application: Application
    private lateinit var stalled: StalledDispatcher

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        E2ETestUtils.resetGrovsSingleton()
        E2ETestUtils.setupTestApplication(application)
        E2ETestUtils.setupMockGlInfo()
        E2ETestUtils.setupMockUserAgent("Grovs SDK threading tests")
        E2ETestUtils.setupMockScreenResolution()
        installStalledDispatcher()
        // Warm-up: the first configure() loads Retrofit/OkHttp/Gson classes, which is JVM cost,
        // not SDK cost. Tests that measure configure() reset and configure again.
        Grovs.configure(application, "test-api-key", useTestEnvironment = true)
    }

    @After
    fun tearDown() {
        E2ETestUtils.resetGrovsSingleton()
        stalled.shutdown()
    }

    private fun installStalledDispatcher() {
        stalled = StalledDispatcher()
        Grovs::class.java.getDeclaredField("grovsContext").apply {
            isAccessible = true
            set(E2ETestUtils.getGrovsInstance(), GrovsContext(serialDispatcher = stalled))
        }
    }

    private fun assertReturnsPromptly(name: String, budgetMs: Long = BUDGET_MS, call: () -> Unit) {
        assertSame("$name must be exercised from the main looper", Looper.getMainLooper(), Looper.myLooper())
        val start = System.nanoTime()
        call()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertFalse(
            "$name blocked the main thread waiting on the SDK serial dispatcher " +
                "(the watchdog had to release the queue after ${elapsedMs}ms)",
            stalled.rescued
        )
        assertTrue("$name took ${elapsedMs}ms on the main thread; budget is ${budgetMs}ms", elapsedMs <= budgetMs)
    }

    // ==================== 1. Fire-and-forget entry points ====================

    @Test
    fun `fire-and-forget entry points return without waiting on the serial dispatcher`() {
        val activity = Robolectric.buildActivity(TestActivity::class.java).get()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("testapp://open?link=abc123"))

        val calls: List<Pair<String, () -> Unit>> = listOf(
            "onStart" to { Grovs.onStart(activity) },
            "onNewIntent" to { Grovs.onNewIntent(intent, activity) },
            "setOnDeeplinkReceivedListener" to { Grovs.setOnDeeplinkReceivedListener(activity) { } },
            "track" to { Grovs.track("checkout_completed", mapOf("total" to 10), listOf("android")) },
            "trackScreenView" to { Grovs.trackScreenView("Home", mapOf("tab" to "feed")) },
            "setGlobalTags" to { Grovs.setGlobalTags(listOf("beta")) },
            "setScreenAliases" to { Grovs.setScreenAliases(mapOf("MainActivity" to "Home")) },
            "logInAppPurchase" to { Grovs.logInAppPurchase("""{"productId":"p1","purchaseToken":"t"}""") },
            "logCustomPurchase" to {
                Grovs.logCustomPurchase(PaymentEventType.BUY, priceInCents = 499, currency = "USD", productId = "p1")
            },
            "identifier setter" to { Grovs.identifier = "user-42" },
            "pushToken setter" to { Grovs.pushToken = "push-token" },
            "attributes setter" to { Grovs.attributes = mapOf("plan" to "pro") },
            "setSDK" to { Grovs.setSDK(true) },
        )

        calls.forEach { (name, call) -> assertReturnsPromptly(name, call = call) }

        assertTrue("The calls must have handed work to the serial dispatcher", stalled.dispatched.get() > 0)
        assertEquals("None of that work may have run on the caller's thread", 0, stalled.executed.get())
    }

    // ==================== 2. configure ====================

    @Test
    fun `configure returns within the main-thread budget without waiting on the serial dispatcher`() {
        E2ETestUtils.resetGrovsSingleton()
        installStalledDispatcher()

        assertReturnsPromptly("configure") {
            Grovs.configure(
                application,
                "test-api-key",
                useTestEnvironment = true,
                baseURL = null,
                autoTrackScreenViews = true,
                clipboardDomains = listOf("grovs.io"),
            )
        }

        assertTrue("configure must schedule authentication rather than run it", stalled.dispatched.get() > 0)
        assertEquals(0, stalled.executed.get())
    }

    // ==================== 3. Activity lifecycle callbacks ====================

    @Test
    fun `activity lifecycle callbacks return promptly with auto screen tracking enabled`() {
        val controller = Robolectric.buildActivity(TestActivity::class.java)
        val mainLooper = Shadows.shadowOf(Looper.getMainLooper())

        // Each transition includes draining the main looper, so work the SDK posts back to main
        // (screen resolution) is measured too: it must resolve the screen and hand off, not wait.
        assertReturnsPromptly("onCreate") { controller.create(); mainLooper.idle() }
        assertReturnsPromptly("onStart") { controller.start(); mainLooper.idle() }
        assertReturnsPromptly("onResume") { controller.resume(); mainLooper.idle() }
        assertReturnsPromptly("onPause") { controller.pause(); mainLooper.idle() }
        assertReturnsPromptly("onStop") { controller.stop(); mainLooper.idle() }
        assertReturnsPromptly("onDestroy") { controller.destroy(); mainLooper.idle() }

        assertTrue("Lifecycle callbacks must hand work to the serial dispatcher", stalled.dispatched.get() > 0)
        assertEquals("None of that work may have run on the main thread", 0, stalled.executed.get())
    }

    companion object {
        private const val BUDGET_MS = 500L
    }
}
