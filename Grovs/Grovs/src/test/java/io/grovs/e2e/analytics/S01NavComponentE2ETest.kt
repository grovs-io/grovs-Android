package io.grovs.e2e.analytics

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.navigation.NavOptions
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.grovs.Grovs
import io.grovs.e2e.E2ETestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Setup 01 — Single-Activity + Jetpack Navigation Component (fragment destinations).
 * Each destination swap drives a real onFragmentResumed that the SDK auto-tracks into a screen_view
 * named after the visible fragment leaf; tests read the emitted sequence back from MockWebServer.
 */
@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class S01NavComponentE2ETest {

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

    // Harness helpers (settle helpers replicated from ScreenTrackingE2ETest).

    private fun configure(autoTrack: Boolean = true) {
        Grovs.configure(
            application = RuntimeEnvironment.getApplication(),
            apiKey = "test-key",
            useTestEnvironment = true,
            baseURL = mockWebServer.url("/").toString(),
            autoTrackScreenViews = autoTrack,
        )
    }

    private suspend fun configureAndAwaitAuth(autoTrack: Boolean = true) {
        configure(autoTrack)
        E2ETestUtils.getAuthenticationJob()?.join()
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

    /** Settles pending resolution, flushes custom events, and returns the ordered screen_view names. */
    private fun screenViews(): List<String> {
        settleAutomaticScreenResolution()
        E2ETestUtils.flushCustomEvents()
        return E2ETestUtils.eventsFromBatchRequests(E2ETestUtils.collectAllRequests(mockWebServer))
            .filter { it.optString("event_name") == "screen_view" }
            .map { it.getJSONObject("properties").getString(SCREEN_NAME) }
    }

    /** Settle + flush + drain, discarding everything emitted so far (keeps dedup state intact). */
    private fun clearBaseline() {
        screenViews()
    }

    private fun buildHost(intent: Intent? = null): ActivityController<S01HostActivity> {
        val controller = if (intent != null) {
            Robolectric.buildActivity(S01HostActivity::class.java, intent)
        } else {
            Robolectric.buildActivity(S01HostActivity::class.java)
        }
        return controller.create().start().resume()
    }

    private fun hostIntent(): Intent =
        Intent(RuntimeEnvironment.getApplication(), S01HostActivity::class.java)

    // Tests

    /** Cold start + forward navigation + Back navigation. */
    @Test
    fun `cold start and forward-back navigation report each destination`() = runTest {
        configureAndAwaitAuth()

        val controller = buildHost()
        val nav = controller.get().navController

        // Cold start -> start destination.
        assertEquals(listOf("S01HomeFragment"), screenViews())

        nav.navigate(S01Routes.DETAIL)
        assertEquals(listOf("S01DetailFragment"), screenViews())

        nav.navigate(S01Routes.SETTINGS)
        assertEquals(listOf("S01SettingsFragment"), screenViews())

        nav.popBackStack()
        assertEquals(listOf("S01DetailFragment"), screenViews())

        nav.popBackStack()
        assertEquals(listOf("S01HomeFragment"), screenViews())
    }

    /** Navigating to Detail with arguments still reports the fragment class name. */
    @Test
    fun `navigate to detail with args reports the fragment name`() = runTest {
        configureAndAwaitAuth()
        val nav = buildHost().get().navController
        clearBaseline() // drop the cold-start Home screen_view

        // Screen name is the fragment's javaClass.simpleName, so Bundle args never change it.
        val args = Bundle().apply { putString("id", "42") }
        nav.navigate(nav.graph.findNode(S01Routes.DETAIL)!!.id, args)

        assertEquals(listOf("S01DetailFragment"), screenViews())
    }

    /** popUpTo(home, inclusive=false) from Settings lands on Home. */
    @Test
    fun `popUpTo home non-inclusive from settings reports home`() = runTest {
        configureAndAwaitAuth()
        val nav = buildHost().get().navController
        nav.navigate(S01Routes.DETAIL)
        nav.navigate(S01Routes.SETTINGS)
        clearBaseline()

        nav.popBackStack(S01Routes.HOME, /* inclusive = */ false)

        assertEquals(listOf("S01HomeFragment"), screenViews())
    }

    /** navigate(home) with popUpTo(home, inclusive=true) rebuilds the start destination. */
    @Test
    fun `popUpTo home inclusive rebuilding start reports home`() = runTest {
        configureAndAwaitAuth()
        val nav = buildHost().get().navController
        nav.navigate(S01Routes.DETAIL)
        clearBaseline()

        val options = NavOptions.Builder()
            .setPopUpTo(S01Routes.HOME, /* inclusive = */ true)
            .build()
        nav.navigate(S01Routes.HOME, options)

        assertEquals(listOf("S01HomeFragment"), screenViews())
    }

    /** Navigating to the destination already on screen is deduped (1s window). */
    @Test
    fun `navigate to same current destination is deduped`() = runTest {
        configureAndAwaitAuth()
        val nav = buildHost().get().navController
        nav.navigate(S01Routes.DETAIL)
        clearBaseline()

        nav.navigate(S01Routes.DETAIL)

        assertEquals(emptyList<String>(), screenViews())
    }

    /** Two navigations in one tick coalesce to the final leaf, not the intermediate one. */
    @Test
    fun `two rapid navigations coalesce to the final destination`() = runTest {
        configureAndAwaitAuth()
        val nav = buildHost().get().navController
        clearBaseline() // drop cold-start Home

        // Both navigate before the looper idles, so only the last resolution job survives (Settings).
        nav.navigate(S01Routes.DETAIL)
        nav.navigate(S01Routes.SETTINGS)

        val views = screenViews()
        assertEquals(listOf("S01SettingsFragment"), views)
        assertFalse("Intermediate Detail must not be emitted", views.contains("S01DetailFragment"))
    }

    /** Deep link straight to Detail reports Detail. */
    @Test
    fun `deep link to detail reports detail`() = runTest {
        configureAndAwaitAuth()
        val nav = buildHost().get().navController
        clearBaseline()

        nav.navigate(android.net.Uri.parse(S01Routes.DEEPLINK_DETAIL))

        assertEquals(listOf("S01DetailFragment"), screenViews())
    }

    /** Deep link to Settings reports Settings (only the top of the synthesized stack resumes). */
    @Test
    fun `deep link to settings reports settings`() = runTest {
        configureAndAwaitAuth()
        val nav = buildHost().get().navController
        clearBaseline()

        nav.navigate(android.net.Uri.parse(S01Routes.DEEPLINK_SETTINGS))

        assertEquals(listOf("S01SettingsFragment"), screenViews())
    }

    /** navigateUp() from Detail returns to Home. */
    @Test
    fun `navigate up from detail reports home`() = runTest {
        configureAndAwaitAuth()
        val nav = buildHost().get().navController
        nav.navigate(S01Routes.DETAIL)
        clearBaseline()

        nav.navigateUp()

        assertEquals(listOf("S01HomeFragment"), screenViews())
    }

    /** Rotation / config-change recreate on Detail re-resolves Detail -> deduped. */
    @Test
    fun `rotation recreate on detail is deduped`() = runTest {
        configureAndAwaitAuth()
        val controller = buildHost()
        controller.get().navController.navigate(S01Routes.DETAIL)
        clearBaseline()

        // Save + restore into a brand-new Activity instance, same SDK singleton (dedup state kept).
        val state = Bundle()
        controller.pause().stop().saveInstanceState(state)
        controller.destroy()

        val restored = Robolectric.buildActivity(S01HostActivity::class.java)
            .create(state).start().resume()
        // Sanity: we really did restore onto Detail.
        assertEquals(
            S01Routes.DETAIL,
            restored.get().navController.currentDestination?.route
        )

        assertEquals(emptyList<String>(), screenViews())
    }

    /** Process death + restore. A restarted process re-inits the SDK, so dedup is fresh. */
    @Test
    fun `process death restore to detail reports detail`() = runTest {
        configureAndAwaitAuth()
        val controller = buildHost()
        controller.get().navController.navigate(S01Routes.DETAIL)
        clearBaseline()

        val state = Bundle()
        controller.pause().stop().saveInstanceState(state)
        controller.destroy()

        // Process killed + restarted: SDK singleton re-created and re-configured, dedup state empty.
        E2ETestUtils.resetGrovsSingleton()
        configureAndAwaitAuth()

        val restored = Robolectric.buildActivity(S01HostActivity::class.java)
            .create(state).start().resume()
        assertEquals(
            S01Routes.DETAIL,
            restored.get().navController.currentDestination?.route
        )

        assertEquals(listOf("S01DetailFragment"), screenViews())
    }

    /** Background then foreground on Detail re-tracks once the 1s dedup window elapses. */
    @Test
    fun `background then foreground on detail retracks after dedup window`() = runTest {
        configureAndAwaitAuth()
        val controller = buildHost()
        controller.get().navController.navigate(S01Routes.DETAIL)
        clearBaseline()

        // Same activity backgrounded then foregrounded; session not rotated, so dedup is not reset.
        controller.pause().stop()
        // Cross the 1000ms wall-clock dedup window so the re-resumed Detail is emitted again.
        Thread.sleep(1_100L)
        controller.start().resume()

        assertEquals(listOf("S01DetailFragment"), screenViews())
    }

    /** Conditional redirect start->Login: Home and Login coalesce to the final leaf. */
    @Test
    fun `conditional redirect at start reports only login`() = runTest {
        configureAndAwaitAuth()

        val intent = hostIntent().putExtra(S01HostActivity.EXTRA_REDIRECT_TO, S01Routes.LOGIN)
        buildHost(intent)

        assertEquals(listOf("S01LoginFragment"), screenViews())
    }

    /** A "global action" to Settings reports Settings (plain navigate path). */
    @Test
    fun `global action to settings reports settings`() = runTest {
        configureAndAwaitAuth()
        val nav = buildHost().get().navController
        nav.navigate(S01Routes.DETAIL)
        clearBaseline()

        // A global action is a convenience wrapper around navigate(destination).
        nav.navigate(S01Routes.SETTINGS)

        assertEquals(listOf("S01SettingsFragment"), screenViews())
    }

    /** Navigate to a DialogFragment destination over Detail, then dismiss it. */
    @Test
    fun `dialog destination over detail and dismiss - actual behavior`() = runTest {
        configureAndAwaitAuth()
        val controller = buildHost()
        val nav = controller.get().navController
        nav.navigate(S01Routes.DETAIL)
        clearBaseline()

        // Show the dialog over Detail.
        nav.navigate(S01Routes.DIALOG)
        val onShow = screenViews()
        println("S01 #19 dialog-open actual = $onShow")

        // Dismiss the dialog back to Detail.
        nav.popBackStack()
        val onDismiss = screenViews()
        println("S01 #20 dialog-dismiss actual = $onDismiss")

        assertEquals(DIALOG_OPEN_EXPECTED, onShow)
        assertEquals(DIALOG_DISMISS_EXPECTED, onDismiss)
    }

    companion object {
        private const val SCREEN_NAME = "screen_name"

        // The DialogFragment is never resolved: the underlying Detail fragment stays the
        // primaryNavigation leaf, so its re-resolution is deduped -> nothing emitted on show, and
        // Detail never re-resumes on dismiss -> nothing emitted then either.
        private val DIALOG_OPEN_EXPECTED = emptyList<String>()
        private val DIALOG_DISMISS_EXPECTED = emptyList<String>()
    }
}
