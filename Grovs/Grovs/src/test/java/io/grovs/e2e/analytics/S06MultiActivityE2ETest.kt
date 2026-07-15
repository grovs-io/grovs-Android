package io.grovs.e2e.analytics

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.grovs.Grovs
import io.grovs.e2e.E2ETestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * S06 — Multi-Activity navigation (classic startActivity stack).
 *
 * Each screen is its own Activity; every resume auto-tracks its simpleName, and fragment-hosting
 * activities report the visible fragment leaf. Transitions the Robolectric controller won't drive
 * (re-resume of an activity kept RESUMED, a foreground/background flip) are dispatched through the
 * SDK's lifecycle observer, which only emits while the target is genuinely at least RESUMED.
 */
@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class S06MultiActivityE2ETest {

    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        E2ETestUtils.resetGrovsSingleton()
        E2ETestUtils.setupMockGlInfo()
        E2ETestUtils.setupTestApplication(RuntimeEnvironment.getApplication())
        E2ETestUtils.setupMockUserAgent(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
        )
        mockWebServer = MockWebServer()
        mockWebServer.start()
        E2ETestUtils.enqueueAuthenticationSuccess(mockWebServer)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        E2ETestUtils.resetGrovsSingleton()
    }

    // ---- Harness ----

    private fun configure(autoTrack: Boolean = true) {
        Grovs.configure(
            application = RuntimeEnvironment.getApplication(),
            apiKey = "test-key",
            useTestEnvironment = true,
            baseURL = mockWebServer.url("/").toString(),
            autoTrackScreenViews = autoTrack,
        )
    }

    private fun settleAutomaticScreenResolution() {
        val mainLooper = Shadows.shadowOf(Looper.getMainLooper())
        mainLooper.idle()

        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L)
        while (pendingScreenResolutionJobs().isNotEmpty()) {
            mainLooper.idle()
            if (System.nanoTime() >= deadlineNanos) {
                throw AssertionError("Timed out waiting for automatic screen resolution")
            }
            Thread.sleep(10L)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun pendingScreenResolutionJobs(): Collection<Job> {
        val jobsField = Grovs::class.java.getDeclaredField("pendingScreenResolutionJobs")
        jobsField.isAccessible = true
        return (jobsField.get(E2ETestUtils.getGrovsInstance()) as ConcurrentHashMap<*, Job>).values
    }

    /** Settles pending resolution jobs and flushes queued screen_view events to MockWebServer. */
    private fun settleAndFlush() {
        settleAutomaticScreenResolution()
        E2ETestUtils.flushCustomEvents()
    }

    /** Drains MockWebServer and returns the ordered screen_name values from screen_view POSTs. */
    private fun drainScreenNames(): List<String> {
        return E2ETestUtils.collectAllRequests(mockWebServer)
            .filter { it.first == "/api/v1/sdk/event/custom" }
            .map { JSONObject(it.second).getJSONObject("properties").getString("screen_name") }
    }

    // ---- SDK lifecycle-observer reflection (for transitions the controller won't drive) ----

    private fun lifecycleObserver(): Application.ActivityLifecycleCallbacks {
        val instance = E2ETestUtils.getGrovsInstance()!!
        val field = Grovs::class.java.getDeclaredField("applicationLifecycleObserver")
        field.isAccessible = true
        return field.get(instance) as Application.ActivityLifecycleCallbacks
    }

    private fun dispatchResumed(activity: Activity) = lifecycleObserver().onActivityResumed(activity)
    private fun dispatchStarted(activity: Activity) = lifecycleObserver().onActivityStarted(activity)
    private fun dispatchStopped(activity: Activity) = lifecycleObserver().onActivityStopped(activity)

    private fun <T : Activity> launch(clazz: Class<T>): T {
        return Robolectric.buildActivity(clazz).create().start().resume().get()
    }

    // ---- Tests ----

    /** Action 1 — Launch Home. */
    @Test
    fun action01_launchHome() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        launch(S06HomeActivity::class.java)
        settleAndFlush()

        assertEquals(listOf("S06HomeActivity"), drainScreenNames())
    }

    /**
     * Actions 2-5 — Home -> Detail -> Settings, then back Settings -> Detail -> Home.
     * All four consecutive names differ, so no dedup collapses them.
     * Back navigation is simulated by re-resuming activities kept in the RESUMED state.
     */
    @Test
    fun action02to05_forwardAndBackChain() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val home = launch(S06HomeActivity::class.java)          // 1: Home
        settleAndFlush()
        val detail = launch(S06DetailActivity::class.java)      // 2: Detail
        settleAndFlush()
        launch(S06SettingsActivity::class.java)                 // 3: Settings
        settleAndFlush()
        dispatchResumed(detail)                                 // 4: back to Detail
        settleAndFlush()
        dispatchResumed(home)                                   // 5: back to Home
        settleAndFlush()

        assertEquals(
            listOf(
                "S06HomeActivity",
                "S06DetailActivity",
                "S06SettingsActivity",
                "S06DetailActivity",
                "S06HomeActivity",
            ),
            drainScreenNames(),
        )
    }

    /** Action 6 — Home -> Detail -> Checkout deep chain. */
    @Test
    fun action06_deepChainToCheckout() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        launch(S06HomeActivity::class.java)
        settleAndFlush()
        launch(S06DetailActivity::class.java)
        settleAndFlush()
        launch(S06CheckoutActivity::class.java)
        settleAndFlush()

        assertEquals(
            listOf("S06HomeActivity", "S06DetailActivity", "S06CheckoutActivity"),
            drainScreenNames(),
        )
    }

    /** Action 7 — Navigate to the same Activity type again (new instance) within 1s dedups by name. */
    @Test
    fun action07_sameActivityTypeDedup() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        launch(S06DetailActivity::class.java) // new instance A
        settleAndFlush()
        launch(S06DetailActivity::class.java) // new instance B, same simpleName within 1s
        settleAndFlush()

        // Only the first Detail is reported; the second is suppressed by the 1s dedup window.
        assertEquals(listOf("S06DetailActivity"), drainScreenNames())
    }

    /**
     * Actions 8 & 9 — A fragment-hosting activity reports the FRAGMENT leaf (activity suppressed),
     * then back to a plain Home reports the activity.
     */
    @Test
    fun action08and09_fragmentHostThenBackHome() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        launch(S06DetailHostActivity::class.java) // 8: hosts S06DetailFragment
        settleAndFlush()
        launch(S06HomeActivity::class.java)       // 9: plain Home
        settleAndFlush()

        assertEquals(
            listOf("S06DetailFragment", "S06HomeActivity"),
            drainScreenNames(),
        )
    }

    /** Action 10 — Deep link launches Detail directly (Detail is the first resumed activity). */
    @Test
    fun action10_deepLinkDirectDetail() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        launch(S06DetailActivity::class.java)
        settleAndFlush()

        assertEquals(listOf("S06DetailActivity"), drainScreenNames())
    }

    /**
     * Action 11 — Deep link launches Detail with a synthetic Home under it. Home is created and
     * started (part of the synthetic back stack) but never resumed, so only Detail is reported.
     */
    @Test
    fun action11_syntheticBackstack() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        // Synthetic Home: created + started, but NOT resumed (it never becomes the foreground screen).
        Robolectric.buildActivity(S06HomeActivity::class.java).create().start()
        // Detail is the activity actually on top and resumed.
        launch(S06DetailActivity::class.java)
        settleAndFlush()

        assertEquals(listOf("S06DetailActivity"), drainScreenNames())
    }

    /** Action 12 — Rotation of Detail (recreate) is deduped: same name within 1s. */
    @Test
    fun action12_rotationDedup() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = Robolectric.buildActivity(S06DetailActivity::class.java).create().start().resume()
        settleAndFlush()
        controller.recreate() // configuration change -> new Detail instance, same simpleName
        settleAndFlush()

        assertEquals(listOf("S06DetailActivity"), drainScreenNames())
    }

    /**
     * Action 13 — Background then foreground Home. The background is far shorter than the 30-min
     * session timeout, so the session does not rotate and dedup is not reset: the re-resume is
     * suppressed as a same-name emit within 1s.
     */
    @Test
    fun action13_backgroundThenForegroundHome() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val home = launch(S06HomeActivity::class.java) // numStarted -> 1, emits Home
        settleAndFlush()

        // Background: app leaves the foreground (numStarted -> 0).
        dispatchStopped(home)
        // Foreground again: numStarted -> 1 (short background: no session rotation, no dedup reset).
        dispatchStarted(home)
        dispatchResumed(home) // Home is still in the RESUMED lifecycle state
        settleAndFlush()

        // First Home reported; the foreground re-resume is deduped (< 1s, same name).
        assertEquals(listOf("S06HomeActivity"), drainScreenNames())
    }

    /**
     * Action 14 — Process death + restore of the top Activity (Checkout). A cold start has a fresh
     * SDK singleton and fresh dedup, so the restored top activity is the only screen reported.
     */
    @Test
    fun action14_processDeathRestoreCheckout() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        launch(S06CheckoutActivity::class.java)
        settleAndFlush()

        assertEquals(listOf("S06CheckoutActivity"), drainScreenNames())
    }

    /** Action 15 — Task switch A -> B -> A quickly. Different names, so NO false dedup. */
    @Test
    fun action15_taskSwitchABANoDedup() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val detail = launch(S06DetailActivity::class.java) // A
        settleAndFlush()
        launch(S06HomeActivity::class.java)                // B
        settleAndFlush()
        dispatchResumed(detail)                            // A again (kept RESUMED)
        settleAndFlush()

        assertEquals(
            listOf("S06DetailActivity", "S06HomeActivity", "S06DetailActivity"),
            drainScreenNames(),
        )
    }

    /**
     * Action 16 — singleTop relaunch of the already-resumed Activity. onNewIntent redelivery goes
     * through onResume again, but the repeated resume of the same screen within 1s is deduped.
     */
    @Test
    fun action16_singleTopRelaunch() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val detail = launch(S06DetailActivity::class.java)
        settleAndFlush()
        dispatchResumed(detail) // singleTop onNewIntent -> onResume redelivery
        settleAndFlush()

        assertEquals(listOf("S06DetailActivity"), drainScreenNames())
    }

    /**
     * Action 17 — Translucent Activity over Home (Home not stopped) then dismissed. The activity
     * below a translucent activity is paused, not stopped, and re-resumes when the translucent one is
     * dismissed — so Home re-resumes and is re-reported (its name differs from the translucent one).
     */
    @Test
    fun action17_translucentOverHome() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val home = launch(S06HomeActivity::class.java)     // Home
        settleAndFlush()
        launch(S06TranslucentActivity::class.java)         // translucent shown on top
        settleAndFlush()
        dispatchResumed(home)                              // translucent dismissed -> Home re-resumes
        settleAndFlush()

        assertEquals(
            listOf("S06HomeActivity", "S06TranslucentActivity", "S06HomeActivity"),
            drainScreenNames(),
        )
    }

    /**
     * Action 18 — Navigate to a NoDisplay/transparent activity. If such an activity actually reaches
     * onResume, the SDK reports it as a screen just like any other activity.
     */
    @Test
    fun action18_noDisplayActivity() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        launch(S06NoDisplayActivity::class.java)
        settleAndFlush()

        assertEquals(listOf("S06NoDisplayActivity"), drainScreenNames())
    }

    /**
     * Action 19 — Per-activity job coalescing.
     * Phase 1: three rapid startActivity resumes (distinct instances) WITHOUT settling — jobs are
     *   keyed per Activity instance, so none is cancelled and all three are reported.
     * Phase 2: the SAME activity instance resumed twice rapidly WITHOUT settling coalesces into one
     *   job (the older pending job is cancelled), so it is reported once.
     */
    @Test
    fun action19_perActivityJobCoalescing() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        // Phase 1: A -> B -> C rapidly, no settle between (distinct instances, distinct job keys).
        launch(S06HomeActivity::class.java)
        launch(S06DetailActivity::class.java)
        launch(S06CheckoutActivity::class.java)
        settleAndFlush()
        assertEquals(
            listOf("S06HomeActivity", "S06DetailActivity", "S06CheckoutActivity"),
            drainScreenNames(),
        )

        // Phase 2: same instance resumed twice with no settle between -> coalesces to one job.
        val settings = launch(S06SettingsActivity::class.java)
        dispatchResumed(settings) // second resume of the SAME instance before the first job runs
        settleAndFlush()
        assertEquals(listOf("S06SettingsActivity"), drainScreenNames())
    }

    /** Action 20 — Activity-alias remapping. Aliases rename the reported activity screen. */
    @Test
    fun action20_activityAlias() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        Grovs.setScreenAliases(mapOf("S06CheckoutActivity" to "Cart"))
        launch(S06CheckoutActivity::class.java)
        settleAndFlush()

        assertEquals(listOf("Cart"), drainScreenNames())
    }
}

// ---- S06 Fixtures ----

/** Base activity: sets an AppCompat theme so Robolectric can resume it without a theme crash. */
open class S06BaseActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        super.onCreate(savedInstanceState)
    }
}

class S06HomeActivity : S06BaseActivity()
class S06DetailActivity : S06BaseActivity()
class S06SettingsActivity : S06BaseActivity()
class S06CheckoutActivity : S06BaseActivity()
class S06TranslucentActivity : S06BaseActivity()
class S06NoDisplayActivity : S06BaseActivity()

/** A fragment leaf hosted by [S06DetailHostActivity]. */
class S06DetailFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply { id = View.generateViewId() }
}

/** An Activity that hosts a single fragment; the SDK must report the fragment leaf, not this. */
class S06DetailHostActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply { id = View.generateViewId() }
        setContentView(root)
        if (savedInstanceState == null) {
            val fragment = S06DetailFragment()
            supportFragmentManager.beginTransaction()
                .add(root.id, fragment, "s06-detail-fragment")
                .setPrimaryNavigationFragment(fragment)
                .commitNow()
        }
    }
}
