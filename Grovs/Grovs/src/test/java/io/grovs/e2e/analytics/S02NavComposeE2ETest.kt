package io.grovs.e2e.analytics

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.navigation.NavDeepLinkRequest
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph
import androidx.navigation.NavGraphNavigator
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Setup 02 — Navigation-Compose / route-based NavController via Grovs.trackNavigation.
 * A Compose app has no Fragments, so lifecycle auto-tracking never sees screen changes.
 * Grovs.trackNavigation(navController) registers an OnDestinationChangedListener that fires
 * immediately with the current destination and again on every change, reporting
 * destination.route ?: label ?: displayName. It is ungated (works with autoTrackScreenViews off)
 * and idempotent per controller.
 */
@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class S02NavComposeE2ETest {

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

    // Harness

    private fun configure(autoTrack: Boolean = true) {
        Grovs.configure(
            application = RuntimeEnvironment.getApplication(),
            apiKey = "test-key",
            useTestEnvironment = true,
            baseURL = mockWebServer.url("/").toString(),
            autoTrackScreenViews = autoTrack,
        )
    }

    // Replicated from ScreenTrackingE2ETest (these helpers are not in E2ETestUtils).
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

    /** Settle + flush + drain, then parse each custom-event POST body's screen_name. Call once per scenario. */
    private fun screenNames(): List<String> {
        settleAutomaticScreenResolution()
        E2ETestUtils.flushCustomEvents()
        return E2ETestUtils.collectAllRequests(mockWebServer)
            .filter { it.first == "/api/v1/sdk/event/custom" }
            .map { (_, body) ->
                JSONObject(body).getJSONObject("properties").getString("screen_name")
            }
    }

    // NavController fixtures

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    /**
     * A single ComposeNavigator destination with the given route, built directly via the navigator:
     * the `composable {}` DSL throws NoSuchMethodError on `composable$default` under this classpath,
     * and building by hand exercises the same route -> NavDestination.route path the SDK reads.
     */
    private fun composeDest(
        nav: TestNavHostController,
        route: String,
        deepLinkUri: String? = null,
    ): NavDestination {
        val dest = nav.navigatorProvider.getNavigator(ComposeNavigator::class.java).createDestination()
        dest.route = route
        if (deepLinkUri != null) dest.addDeepLink(deepLinkUri)
        return dest
    }

    /**
     * A route-based controller mirroring a Navigation-Compose graph: top-level destinations plus a
     * nested navigation graph (route "nested", start "nestedStart").
     */
    private fun newController(startDestination: String = "home"): TestNavHostController {
        val navController = TestNavHostController(context())
        navController.navigatorProvider.addNavigator(ComposeNavigator())
        val graphNavigator = navController.navigatorProvider.getNavigator(NavGraphNavigator::class.java)
        val graph = NavGraph(graphNavigator).apply {
            route = "s02root"
            addDestination(composeDest(navController, "home"))
            addDestination(composeDest(navController, "detail", deepLinkUri = "s02app://detail"))
            addDestination(composeDest(navController, "detail/{id}"))
            addDestination(composeDest(navController, "settings"))
            addDestination(composeDest(navController, "search"))
            addDestination(composeDest(navController, "profile"))
            val nested = NavGraph(graphNavigator).apply {
                route = "nested"
                addDestination(composeDest(navController, "nestedStart"))
                addDestination(composeDest(navController, "nestedSecond"))
                setStartDestination("nestedStart")
            }
            addDestination(nested)
            setStartDestination(startDestination)
        }
        navController.graph = graph
        return navController
    }

    // Tests

    // Immediate fire on attach, one event per navigate (no coalescing), pops re-emit the revealed route.
    @Test
    fun `immediate fire then one event per navigation and pop`() = runTest {
        configure(autoTrack = true)
        E2ETestUtils.getAuthenticationJob()?.join()

        val nav = newController(startDestination = "home")
        Grovs.trackNavigation(nav)          // immediate -> home
        nav.navigate("detail")              // -> detail
        nav.navigate("settings")            // -> settings
        nav.popBackStack()                  // settings -> detail
        nav.popBackStack()                  // detail -> home

        assertEquals(listOf("home", "detail", "settings", "detail", "home"), screenNames())
    }

    // Navigating an arg route "detail/42" reports the route pattern "detail/{id}", not the value.
    @Test
    fun `arg route reports the route pattern not the filled value`() = runTest {
        configure(autoTrack = true)
        E2ETestUtils.getAuthenticationJob()?.join()

        val nav = newController(startDestination = "home")
        Grovs.trackNavigation(nav)          // immediate -> home
        nav.navigate("detail/42")           // -> detail/{id}

        assertEquals(listOf("home", "detail/{id}"), screenNames())
    }

    // Navigating to the same resolved route within the 1s dedup window emits nothing further.
    @Test
    fun `re-navigating the same route is deduped`() = runTest {
        configure(autoTrack = true)
        E2ETestUtils.getAuthenticationJob()?.join()

        val nav = newController(startDestination = "home")
        Grovs.trackNavigation(nav)          // immediate -> home
        nav.navigate("detail")              // -> detail
        nav.navigate("detail")              // same route -> deduped

        assertEquals(listOf("home", "detail"), screenNames())
    }

    // Navigating via a NavDeepLinkRequest resolves to the target destination and reports its route.
    @Test
    fun `deep link navigation reports the target route`() = runTest {
        configure(autoTrack = true)
        E2ETestUtils.getAuthenticationJob()?.join()

        val nav = newController(startDestination = "home")
        Grovs.trackNavigation(nav)          // immediate -> home
        nav.navigate(
            NavDeepLinkRequest.Builder.fromUri(Uri.parse("s02app://detail")).build()
        )                                    // -> detail

        assertEquals(listOf("home", "detail"), screenNames())
    }

    // Navigating to a nested graph lands on its start destination; popping re-emits the parent route.
    @Test
    fun `nested graph reports nested start then parent route on back`() = runTest {
        configure(autoTrack = true)
        E2ETestUtils.getAuthenticationJob()?.join()

        val nav = newController(startDestination = "home")
        Grovs.trackNavigation(nav)          // immediate -> home
        nav.navigate("nested")              // -> nestedStart (nested graph start dest)
        nav.popBackStack()                  // back to parent -> home

        assertEquals(listOf("home", "nestedStart", "home"), screenNames())
    }

    // Top-level tab switches emit each selected route; reselecting the current tab is deduped.
    @Test
    fun `top-level tab switches emit each route and reselect is deduped`() = runTest {
        configure(autoTrack = true)
        E2ETestUtils.getAuthenticationJob()?.join()

        val nav = newController(startDestination = "home")
        Grovs.trackNavigation(nav)          // immediate -> home
        nav.navigate("search")              // -> search
        nav.navigate("profile")             // -> profile
        nav.navigate("home")                // -> home
        nav.navigate("home")                // reselect -> deduped

        assertEquals(listOf("home", "search", "profile", "home"), screenNames())
    }

    // A config-change recreation produces a brand-new controller; per-controller idempotency does not
    // suppress it, so trackNavigation fires immediately for its current destination.
    @Test
    fun `a genuinely new controller is not suppressed by idempotency`() = runTest {
        configure(autoTrack = true)
        E2ETestUtils.getAuthenticationJob()?.join()

        val first = newController(startDestination = "home")
        Grovs.trackNavigation(first)        // immediate -> home
        first.navigate("detail")            // -> detail

        // Recreate (as on a configuration change): a different controller instance.
        val recreated = newController(startDestination = "settings")
        val added = attachIsFresh(recreated)
        Grovs.trackNavigation(recreated)    // immediate -> settings (must NOT be suppressed)

        assertTrue("A new controller must be treated as untracked", added)
        assertEquals(listOf("home", "detail", "settings"), screenNames())
    }

    // Calling trackNavigation twice on the same controller registers a single listener, so a
    // subsequent navigate emits exactly one event.
    @Test
    fun `double attach on the same controller yields a single listener`() = runTest {
        configure(autoTrack = true)
        E2ETestUtils.getAuthenticationJob()?.join()

        val nav = newController(startDestination = "home")
        Grovs.trackNavigation(nav)          // immediate -> home ; registers listener
        Grovs.trackNavigation(nav)          // idempotent: no second listener, no immediate re-fire
        nav.navigate("detail")              // -> detail exactly once

        // [home, detail] — if two listeners were registered we'd see [home, detail, detail].
        assertEquals(listOf("home", "detail"), screenNames())
    }

    // trackNavigation is ungated: it emits even with autoTrackScreenViews = false.
    @Test
    fun `trackNavigation emits even when auto-tracking is off`() = runTest {
        configure(autoTrack = false)
        E2ETestUtils.getAuthenticationJob()?.join()

        val nav = newController(startDestination = "home")
        Grovs.trackNavigation(nav)          // immediate -> home
        nav.navigate("detail")              // -> detail

        assertEquals(listOf("home", "detail"), screenNames())
    }

    // When a destination has both a route and a label, the route wins.
    @Test
    fun `route wins over label`() = runTest {
        configure(autoTrack = true)
        E2ETestUtils.getAuthenticationJob()?.join()

        val nav = newController(startDestination = "home")
        nav.graph.findNode("settings")?.label = "S02SettingsLabel"
        Grovs.trackNavigation(nav)          // immediate -> home
        nav.navigate("settings")            // route "settings" wins over label

        assertEquals(listOf("home", "settings"), screenNames())
    }

    // A destination with route == null but a label set reports the label.
    @Test
    fun `label is used when route is null`() = runTest {
        configure(autoTrack = true)
        E2ETestUtils.getAuthenticationJob()?.join()

        val nav = labelOnlyController(label = "S02SettingsLabel")
        Grovs.trackNavigation(nav)          // immediate -> current dest (route null) -> label

        assertEquals(listOf("S02SettingsLabel"), screenNames())
    }

    // A destination with neither route nor label reports a non-blank displayName.
    @Test
    fun `displayName is used when neither route nor label is set`() = runTest {
        configure(autoTrack = true)
        E2ETestUtils.getAuthenticationJob()?.join()

        val nav = labelOnlyController(label = null)
        Grovs.trackNavigation(nav)          // immediate -> current dest -> displayName

        val names = screenNames()
        assertEquals(1, names.size)
        assertTrue("displayName must be non-blank, was '${names.firstOrNull()}'", names.single().isNotBlank())
    }

    // After the tracked controller is dropped and GC'd, tracking a fresh controller still works: the
    // listener lives and dies with its controller and only a weak ref is held for dedup.
    @Test
    fun `dropping a tracked controller does not crash later tracking`() = runTest {
        configure(autoTrack = true)
        E2ETestUtils.getAuthenticationJob()?.join()

        var doomed: TestNavHostController? = newController(startDestination = "home")
        Grovs.trackNavigation(doomed!!)     // immediate -> home
        doomed = null

        System.gc()
        Thread.sleep(50L)
        System.gc()

        val fresh = newController(startDestination = "home")
        Grovs.trackNavigation(fresh)        // immediate -> home (deduped against the first home)
        fresh.navigate("detail")            // -> detail

        val names = screenNames()
        assertTrue("Fresh controller must still emit after GC of the old one", names.contains("detail"))
        assertFalse("No spurious empties", names.any { it.isBlank() })
    }

    // Helpers for label/displayName-only destinations (no route-based DSL can express route=null).

    private fun labelOnlyController(label: CharSequence?): TestNavHostController {
        val navController = TestNavHostController(context())
        navController.navigatorProvider.addNavigator(ComposeNavigator())
        val provider = navController.navigatorProvider
        val composeNavigator = provider.getNavigator(ComposeNavigator::class.java)
        val leaf = composeNavigator.createDestination().apply {
            id = 0x02_0001
            if (label != null) this.label = label
            // route intentionally left null
        }
        val graphNavigator = provider.getNavigator(NavGraphNavigator::class.java)
        val graph = NavGraph(graphNavigator).apply {
            id = 0x02_0000
            addDestination(leaf)
            setStartDestination(0x02_0001)
        }
        navController.graph = graph
        return navController
    }

    /** True if this controller is not yet tracked by NavigationScreenTracker (i.e. a fresh attach). */
    private fun attachIsFresh(controller: androidx.navigation.NavController): Boolean {
        val trackerClass = Class.forName("io.grovs.handlers.NavigationScreenTracker")
        val instance = trackerClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        @Suppress("UNCHECKED_CAST")
        val tracked = trackerClass.getDeclaredField("tracked").apply { isAccessible = true }
            .get(instance) as MutableSet<androidx.navigation.NavController>
        return !tracked.contains(controller)
    }
}
