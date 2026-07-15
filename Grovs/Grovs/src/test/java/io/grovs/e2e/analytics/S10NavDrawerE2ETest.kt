package io.grovs.e2e.analytics

import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.createGraph
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.fragment
import androidx.navigation.navOptions
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
 * Setup 10 — navigation drawer (drawer item -> fragment swap). Two wirings are exercised:
 *   (a) drawer wired to a NavController with fragment destinations — [S10NavHostActivity].
 *   (b) drawer that manually replace()s the content fragment — [S10ReplaceHostActivity].
 *
 * Opening/closing the drawer is a UI state change with no fragment lifecycle, so it must not emit
 * a screen; only the content swap does.
 */
@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class S10NavDrawerE2ETest {

    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        E2ETestUtils.resetGrovsSingleton()
        E2ETestUtils.setupMockGlInfo()
        E2ETestUtils.setupTestApplication(RuntimeEnvironment.getApplication())
        E2ETestUtils.setupMockUserAgent(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
        )
        // Reset fixture config so tests are order-independent.
        S10ReplaceHostActivity.initialRoute = S10Routes.HOME
        S10NavHostActivity.startRoute = S10Routes.HOME
        mockWebServer = MockWebServer()
        mockWebServer.start()
        E2ETestUtils.enqueueAuthenticationSuccess(mockWebServer)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        E2ETestUtils.resetGrovsSingleton()
    }

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

    /** Settle resolution, flush the custom-event queue, then read every emitted screen_view in order. */
    private fun collectScreenViews(): List<String> {
        settleAutomaticScreenResolution()
        E2ETestUtils.flushCustomEvents()
        return E2ETestUtils.collectAllRequests(mockWebServer)
            .filter { it.first == "/api/v1/sdk/event/custom" }
            .mapNotNull { (_, body) ->
                try {
                    val json = JSONObject(body)
                    if (json.optString("event_name") == "screen_view") {
                        json.getJSONObject("properties").getString("screen_name")
                    } else null
                } catch (e: Exception) {
                    null
                }
            }
    }

    private fun idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    // Wiring (a): NavController-driven fragment destinations.
    @Test
    fun `navController drawer journey - home feed profile settings home, reselect is a no-op`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = Robolectric.buildActivity(S10NavHostActivity::class.java)
            .create().start().resume()
        val activity = controller.get()

        // Cold start shows the Home content.
        settleAutomaticScreenResolution()

        // Open the drawer: pure UI state, no fragment lifecycle -> nothing emitted.
        activity.openDrawer()
        settleAutomaticScreenResolution()

        // Close the drawer without selecting anything -> still no fragment lifecycle.
        activity.closeDrawer()
        settleAutomaticScreenResolution()

        // Select Feed, Profile, Settings via the NavController.
        activity.selectDrawerItem_NavController(S10Routes.FEED)
        settleAutomaticScreenResolution()
        activity.selectDrawerItem_NavController(S10Routes.PROFILE)
        settleAutomaticScreenResolution()
        activity.selectDrawerItem_NavController(S10Routes.SETTINGS)
        settleAutomaticScreenResolution()

        // Select Home again from Settings.
        activity.selectDrawerItem_NavController(S10Routes.HOME)
        settleAutomaticScreenResolution()

        // Reselect the current item (Home while on Home) -> singleTop no-op.
        activity.selectDrawerItem_NavController(S10Routes.HOME)
        settleAutomaticScreenResolution()

        val screens = collectScreenViews()
        assertEquals(
            listOf(
                "S10HomeFragment",
                "S10FeedFragment",
                "S10ProfileFragment",
                "S10SettingsFragment",
                "S10HomeFragment",
            ),
            screens,
        )
    }

    // Wiring (b): manual replace()+commit content swaps.
    @Test
    fun `manual replace drawer swaps emit the swapped-in fragment`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = Robolectric.buildActivity(S10ReplaceHostActivity::class.java)
            .create().start().resume()
        val activity = controller.get()
        settleAutomaticScreenResolution() // Home content.

        // Select Feed via manual replace().
        activity.selectDrawerItem_Replace(S10FeedFragment())
        settleAutomaticScreenResolution()

        // Feed -> Profile via manual replace().
        activity.selectDrawerItem_Replace(S10ProfileFragment())
        settleAutomaticScreenResolution()

        // Select the same content fragment class twice -> second is deduped.
        activity.selectDrawerItem_Replace(S10ProfileFragment())
        settleAutomaticScreenResolution()

        val screens = collectScreenViews()
        assertEquals(
            listOf("S10HomeFragment", "S10FeedFragment", "S10ProfileFragment"),
            screens,
        )
    }

    // Manual replace with addToBackStack: system back re-resumes the revealed fragment
    // (unlike hide()/show(), which would not re-resume).
    @Test
    fun `manual replace back-stack re-emits the revealed fragment on system back`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = Robolectric.buildActivity(S10ReplaceHostActivity::class.java)
            .create().start().resume()
        val activity = controller.get()
        settleAutomaticScreenResolution() // Home.

        // Move to Feed on the back stack.
        activity.selectDrawerItem_Replace(S10FeedFragment(), addToBackStack = true)
        settleAutomaticScreenResolution()

        // Open a detail under Feed (also on the back stack).
        activity.selectDrawerItem_Replace(S10FeedDetailFragment(), addToBackStack = true)
        settleAutomaticScreenResolution()

        // System back from the detail -> Feed is revealed and re-resumed.
        activity.systemBack()
        settleAutomaticScreenResolution()

        // System back again -> Home is revealed and re-resumed.
        activity.systemBack()
        settleAutomaticScreenResolution()

        val screens = collectScreenViews()
        assertEquals(
            listOf(
                "S10HomeFragment",
                "S10FeedFragment",
                "S10FeedDetailFragment",
                "S10FeedFragment",
                "S10HomeFragment",
            ),
            screens,
        )
    }

    // Rapid drawer selections in one tick coalesce to the last destination.
    @Test
    fun `rapid drawer selections in one tick coalesce to the last destination`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = Robolectric.buildActivity(S10ReplaceHostActivity::class.java)
            .create().start().resume()
        val activity = controller.get()
        settleAutomaticScreenResolution() // Home.

        // Three selections without settling in between -> lifecycle callbacks coalesce into the
        // single most-recent resolution job, which resolves to the final visible leaf.
        activity.selectDrawerItem_Replace(S10FeedFragment())
        activity.selectDrawerItem_Replace(S10ProfileFragment())
        activity.selectDrawerItem_Replace(S10SettingsFragment())
        settleAutomaticScreenResolution()

        val screens = collectScreenViews()
        assertEquals(listOf("S10HomeFragment", "S10SettingsFragment"), screens)
    }

    // Rotation (recreate) on Profile -> the re-resumed Profile is deduped.
    @Test
    fun `rotation on profile does not re-emit within the dedup window`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        S10ReplaceHostActivity.initialRoute = S10Routes.PROFILE
        val controller = Robolectric.buildActivity(S10ReplaceHostActivity::class.java)
            .create().start().resume()
        settleAutomaticScreenResolution() // Profile.

        controller.configurationChange() // rotation: destroy + recreate, restoring Profile.
        settleAutomaticScreenResolution()

        val screens = collectScreenViews()
        assertEquals(listOf("S10ProfileFragment"), screens)
    }

    // Background/foreground on Feed.
    @Test
    fun `background then foreground on feed does not re-emit within dedup window`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        S10ReplaceHostActivity.initialRoute = S10Routes.FEED
        val controller = Robolectric.buildActivity(S10ReplaceHostActivity::class.java)
            .create().start().resume()
        settleAutomaticScreenResolution() // Feed.

        controller.pause().stop() // background
        idle()
        controller.start().resume() // foreground
        settleAutomaticScreenResolution()

        val screens = collectScreenViews()
        // No session rotation happens on an instantaneous background/foreground in the test, so the
        // dedup window is preserved and the re-resumed Feed is suppressed.
        assertEquals(listOf("S10FeedFragment"), screens)
    }

    // Process-death restore on Settings -> a fresh process (fresh dedup) emits Settings.
    @Test
    fun `process death restore on settings emits settings`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        // A brand-new process: the SDK has just been configured (its dedup state is empty) and the
        // restored Activity lands on Settings.
        S10ReplaceHostActivity.initialRoute = S10Routes.SETTINGS
        Robolectric.buildActivity(S10ReplaceHostActivity::class.java)
            .create().start().resume()

        val screens = collectScreenViews()
        assertEquals(listOf("S10SettingsFragment"), screens)
    }

    // Deep link that selects Profile on launch -> only Profile.
    @Test
    fun `deep link selecting profile on launch emits only profile`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        // The deep link makes Profile the launch destination of the NavController graph.
        S10NavHostActivity.startRoute = S10Routes.PROFILE
        Robolectric.buildActivity(S10NavHostActivity::class.java)
            .create().start().resume()

        val screens = collectScreenViews()
        assertEquals(listOf("S10ProfileFragment"), screens)
    }

    // A drawer item that launches a separate Activity -> the new Activity is tracked.
    @Test
    fun `drawer item launching a separate activity emits the activity screen`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val hostController = Robolectric.buildActivity(S10ReplaceHostActivity::class.java)
            .create().start().resume()
        settleAutomaticScreenResolution() // Home.

        // Drawer selection navigates out to a separate (plain, fragment-less) Activity.
        hostController.pause()
        idle()
        Robolectric.buildActivity(S10OtherActivity::class.java).create().start().resume()
        settleAutomaticScreenResolution()

        val screens = collectScreenViews()
        assertEquals(listOf("S10HomeFragment", "S10OtherActivity"), screens)
    }
}

// Fixtures (all prefixed S10).

object S10Routes {
    const val HOME = "home"
    const val FEED = "feed"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"
    const val FEED_DETAIL = "feed_detail"
}

/** Base drawer content fragment: a plain visible FrameLayout so the resolver treats it as eligible. */
open class S10ContentFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply { id = View.generateViewId() }
}

class S10HomeFragment : S10ContentFragment()
class S10FeedFragment : S10ContentFragment()
class S10ProfileFragment : S10ContentFragment()
class S10SettingsFragment : S10ContentFragment()
class S10FeedDetailFragment : S10ContentFragment()

private fun s10FragmentForRoute(route: String): Fragment = when (route) {
    S10Routes.FEED -> S10FeedFragment()
    S10Routes.PROFILE -> S10ProfileFragment()
    S10Routes.SETTINGS -> S10SettingsFragment()
    S10Routes.FEED_DETAIL -> S10FeedDetailFragment()
    else -> S10HomeFragment()
}

/**
 * Wiring (a): a drawer host backed by a Jetpack [NavController]. Selecting a drawer item navigates
 * the NavController's fragment graph; the resumed destination fragment is auto-tracked by the
 * lifecycle resolver. openDrawer/closeDrawer are no-ops (no fragment lifecycle).
 */
class S10NavHostActivity : AppCompatActivity() {

    companion object {
        /** Launch destination; a deep link would set this before the Activity is created. */
        var startRoute: String = S10Routes.HOME
    }

    lateinit var navController: NavController
        private set

    private var drawerOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply { id = View.generateViewId() }
        setContentView(root)

        if (savedInstanceState == null) {
            val navHostFragment = NavHostFragment()
            supportFragmentManager.beginTransaction()
                .add(root.id, navHostFragment)
                .setPrimaryNavigationFragment(navHostFragment)
                .commitNow()

            navController = navHostFragment.navController
            navController.graph = navController.createGraph(startDestination = startRoute) {
                fragment<S10HomeFragment>(S10Routes.HOME)
                fragment<S10FeedFragment>(S10Routes.FEED)
                fragment<S10ProfileFragment>(S10Routes.PROFILE)
                fragment<S10SettingsFragment>(S10Routes.SETTINGS)
                fragment<S10FeedDetailFragment>(S10Routes.FEED_DETAIL)
            }
        } else {
            val navHostFragment =
                supportFragmentManager.primaryNavigationFragment as NavHostFragment
            navController = navHostFragment.navController
        }
    }

    /** Drawer open/close: pure UI state, no fragment lifecycle. */
    fun openDrawer() { drawerOpen = true }
    fun closeDrawer() { drawerOpen = false }

    fun selectDrawerItem_NavController(route: String) {
        // launchSingleTop mirrors a drawer reselect: navigating to the current destination is a no-op.
        navController.navigate(route, navOptions { launchSingleTop = true })
        supportFragmentManager.executePendingTransactions()
        (supportFragmentManager.primaryNavigationFragment as? NavHostFragment)
            ?.childFragmentManager?.executePendingTransactions()
    }
}

/**
 * Wiring (b): a drawer host that manually replace()s the content fragment. openDrawer/closeDrawer
 * are no-ops (no fragment lifecycle).
 */
class S10ReplaceHostActivity : AppCompatActivity() {

    companion object {
        /** Which content fragment is shown on a fresh create (models process-death restore / deep links). */
        var initialRoute: String = S10Routes.HOME
    }

    private var containerId: Int = 0
    private var drawerOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply { id = View.generateViewId() }
        setContentView(root)
        containerId = root.id

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(containerId, s10FragmentForRoute(initialRoute))
                .commitNow()
        }
    }

    fun openDrawer() { drawerOpen = true }
    fun closeDrawer() { drawerOpen = false }

    fun selectDrawerItem_Replace(fragment: Fragment, addToBackStack: Boolean = false) {
        val tx = supportFragmentManager.beginTransaction().replace(containerId, fragment)
        if (addToBackStack) tx.addToBackStack(null)
        tx.commit()
        supportFragmentManager.executePendingTransactions()
    }

    fun systemBack() {
        supportFragmentManager.popBackStack()
        supportFragmentManager.executePendingTransactions()
    }
}

/** A plain, fragment-less Activity a drawer item can launch. */
class S10OtherActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this).apply { id = View.generateViewId() })
    }
}
