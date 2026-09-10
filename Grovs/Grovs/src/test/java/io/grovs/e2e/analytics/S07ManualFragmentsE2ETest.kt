package io.grovs.e2e.analytics

import android.os.Bundle
import android.os.Looper
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
import org.junit.Assert.assertTrue
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
 * Setup 07 — Manual FragmentTransactions with back stack (replace + addToBackStack).
 *
 * Verifies the Grovs automatic screen-view tracker against hand-rolled fragment navigation.
 * See S07Fixtures.kt for the host Activity and fragments.
 *
 * A screen_view is one POST to /api/v1/sdk/events/batch carrying properties.screen_name.
 *
 * Each distinct navigation is followed by settleAutomaticScreenResolution() to run its resolution
 * job — a real looper turn would do the same. Tests that probe coalescing withhold that settle.
 */
@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class S07ManualFragmentsE2ETest {

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

    // harness

    private fun configure(autoTrack: Boolean = true) {
        Grovs.configure(
            application = RuntimeEnvironment.getApplication(),
            apiKey = "test-key",
            useTestEnvironment = true,
            baseURL = mockWebServer.url("/").toString(),
            autoTrackScreenViews = autoTrack,
        )
    }

    // Replicated from ScreenTrackingE2ETest — NOT exposed by E2ETestUtils.
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

    /**
     * Flushes stored custom events, drains the MockWebServer, and returns the ordered screen_name
     * list emitted SINCE the previous call (the server queue and the events storage are both drained,
     * so consecutive calls read only what is new). Also settles any still-pending resolution first.
     */
    private fun drainEmittedScreens(): List<String> {
        settleAutomaticScreenResolution()
        E2ETestUtils.flushCustomEvents()
        return E2ETestUtils.collectAllRequests(mockWebServer)
            .filter { it.first == "/api/v1/sdk/events/batch" }
            .flatMap { (_, body) ->
                val events = JSONObject(body).getJSONArray("events")
                (0 until events.length()).map { events.getJSONObject(it) }
            }
            .map { it.getJSONObject("properties").getString("screen_name") }
    }

    /** Build the host resumed (root Home installed) and run Home's resolution job. */
    private fun buildHostSettled(): ActivityController<S07HostActivity> {
        val controller = Robolectric.buildActivity(S07HostActivity::class.java)
            .create().start().resume()
        settleAutomaticScreenResolution()
        return controller
    }

    // A distinct navigation followed by the looper turn that resolves it.
    private fun push(activity: S07HostActivity, fragment: Fragment) {
        activity.push(fragment); settleAutomaticScreenResolution()
    }

    private fun pushNamed(activity: S07HostActivity, fragment: Fragment, name: String) {
        activity.pushNamed(fragment, name); settleAutomaticScreenResolution()
    }

    private fun pushAdd(activity: S07HostActivity, fragment: Fragment) {
        activity.pushAdd(fragment); settleAutomaticScreenResolution()
    }

    private fun pop(activity: S07HostActivity) {
        activity.pop(); settleAutomaticScreenResolution()
    }

    // tests

    // Actions 1-7: initial Home, forward replace()→List→Detail→Edit, then popBackStack back through
    // Detail→List→Home. Each replace() and each pop() resumes a fragment with a distinct name, so
    // every step emits.
    @Test
    fun `forward and back journey emits every distinct screen (actions 1-7)`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val activity = buildHostSettled().get()

        push(activity, S07ListFragment())
        push(activity, S07DetailFragment())
        push(activity, S07EditFragment())
        pop(activity) // Edit -> Detail
        pop(activity) // Detail -> List
        pop(activity) // List -> Home

        assertEquals(
            listOf(
                "S07HomeFragment",
                "S07ListFragment",
                "S07DetailFragment",
                "S07EditFragment",
                "S07DetailFragment",
                "S07ListFragment",
                "S07HomeFragment",
            ),
            drainEmittedScreens(),
        )
    }

    // Action 8: a single inclusive multi-level pop (Detail+List popped at once) reveals Home and
    // resumes it exactly once — the intermediate levels never resume, so only the final Home emits.
    @Test
    fun `inclusive multi-level pop coalesces to the final revealed screen (action 8)`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val activity = buildHostSettled().get()
        pushNamed(activity, S07ListFragment(), "list-entry")
        push(activity, S07DetailFragment())

        assertEquals(
            listOf("S07HomeFragment", "S07ListFragment", "S07DetailFragment"),
            drainEmittedScreens(),
        )

        activity.popInclusive("list-entry") // pops Detail + List in one shot -> Home
        assertEquals(listOf("S07HomeFragment"), drainEmittedScreens())
    }

    // Actions 9 & 10: add()-on-top resumes the added Detail (tracked). Popping it reveals the List
    // that was never stopped, so List is NOT re-resumed -> nothing emits. This is the add()-vs-
    // replace() gap.
    @Test
    fun `add on top tracks, but popping the added fragment does not re-emit the revealed one (actions 9-10)`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val activity = buildHostSettled().get()
        push(activity, S07ListFragment())
        pushAdd(activity, S07DetailFragment()) // add on top of List

        assertEquals(
            listOf("S07HomeFragment", "S07ListFragment", "S07DetailFragment"),
            drainEmittedScreens(),
        )

        pop(activity) // remove added Detail; List underneath was never stopped
        // add()-reveal gap: List is not re-resumed, so nothing emits (would ideally be [S07ListFragment]).
        assertEquals(emptyList<String>(), drainEmittedScreens())
    }

    // Action 11: replacing with another instance of the SAME class resolves to the same name within
    // the 1s dedup window -> the second one is suppressed.
    @Test
    fun `replacing with the same fragment class is deduped within the window (action 11)`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val activity = buildHostSettled().get()
        push(activity, S07DetailFragment())
        push(activity, S07DetailFragment()) // same class again, < 1s later

        assertEquals(listOf("S07HomeFragment", "S07DetailFragment"), drainEmittedScreens())
    }

    // Action 12: two rapid replace() transactions (List->Detail->Edit) before the looper drains
    // coalesce into a single most-recent resolution job -> only the final Edit emits.
    @Test
    fun `rapid consecutive replaces coalesce to the final screen (action 12)`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val activity = buildHostSettled().get()
        push(activity, S07ListFragment())
        assertEquals(listOf("S07HomeFragment", "S07ListFragment"), drainEmittedScreens())

        // Two transactions, executed back-to-back, WITHOUT settling in between (one "navigation").
        activity.supportFragmentManager.beginTransaction()
            .replace(S07HostActivity.CONTAINER_ID, S07DetailFragment())
            .addToBackStack(null).commit()
        activity.supportFragmentManager.beginTransaction()
            .replace(S07HostActivity.CONTAINER_ID, S07EditFragment())
            .addToBackStack(null).commit()
        activity.supportFragmentManager.executePendingTransactions()

        assertEquals(listOf("S07EditFragment"), drainEmittedScreens())
    }

    // Action 13: rotation recreates the Activity; the restored Detail resumes again, resolving to the
    // same name within 1s -> deduped, nothing new.
    @Test
    fun `rotation on Detail is deduped within the window (action 13)`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = buildHostSettled()
        val activity = controller.get()
        push(activity, S07DetailFragment())
        assertEquals(listOf("S07HomeFragment", "S07DetailFragment"), drainEmittedScreens())

        controller.recreate() // configuration change
        assertEquals(emptyList<String>(), drainEmittedScreens())
    }

    // Action 14: background then foreground within the same session (no session rotation) leaves the
    // dedup state intact, so the re-resumed Detail is suppressed.
    @Test
    fun `background then foreground on Detail is deduped, no session rotation (action 14)`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = buildHostSettled()
        val activity = controller.get()
        push(activity, S07DetailFragment())
        assertEquals(listOf("S07HomeFragment", "S07DetailFragment"), drainEmittedScreens())

        controller.pause().stop()   // background
        controller.start().resume() // foreground (same session -> dedup NOT reset)

        assertEquals(emptyList<String>(), drainEmittedScreens())
    }

    // Action 15: process death + restore. A fresh SDK process has fresh dedup state, and restoring the
    // back stack resumes the top fragment (Edit) -> it emits.
    @Test
    fun `process death restore resumes and emits the restored top screen (action 15)`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = buildHostSettled()
        val activity = controller.get()
        push(activity, S07ListFragment())
        push(activity, S07DetailFragment())
        push(activity, S07EditFragment())
        // Drain everything emitted so far AND empty the events storage before the "process death".
        assertEquals(
            listOf("S07HomeFragment", "S07ListFragment", "S07DetailFragment", "S07EditFragment"),
            drainEmittedScreens(),
        )

        val savedState = Bundle()
        controller.saveInstanceState(savedState)

        // Simulate process death: tear the SDK singleton down and reconfigure it fresh.
        E2ETestUtils.resetGrovsSingleton()
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        // Restore with the saved back stack; onCreate skips re-adding Home, FragmentManager restores Edit.
        Robolectric.buildActivity(S07HostActivity::class.java)
            .create(savedState).start().resume()

        assertEquals(listOf("S07EditFragment"), drainEmittedScreens())
    }

    // Action 16: commitNow() (synchronous resume) and commit()+settle each yield exactly one emission
    // for their transition — no double-tracking.
    @Test
    fun `commitNow and commit+settle each emit exactly once (action 16)`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val activity = buildHostSettled().get()

        // commitNow: resumes List synchronously.
        activity.supportFragmentManager.beginTransaction()
            .replace(S07HostActivity.CONTAINER_ID, S07ListFragment())
            .commitNow()
        settleAutomaticScreenResolution()

        // commit + settle.
        activity.supportFragmentManager.beginTransaction()
            .replace(S07HostActivity.CONTAINER_ID, S07EditFragment())
            .addToBackStack(null).commit()
        activity.supportFragmentManager.executePendingTransactions()
        settleAutomaticScreenResolution()

        assertEquals(
            listOf("S07HomeFragment", "S07ListFragment", "S07EditFragment"),
            drainEmittedScreens(),
        )
    }

    // Action 17: a Detail hosting a child fragment -> the resolver descends into the childFragmentManager
    // and reports the child leaf.
    @Test
    fun `nested child fragment is reported as the leaf (action 17)`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val activity = buildHostSettled().get()
        push(activity, S07NestedDetailFragment())

        assertEquals(listOf("S07HomeFragment", "S07DetailChildFragment"), drainEmittedScreens())
    }

    // Action 18: a DialogFragment shown over Detail is a modal overlay, NOT a screen — the resolver
    // skips DialogFragments, so showing it emits nothing (Detail stays the current screen and is
    // deduped). Dismissing it also does not re-resume Detail, so nothing re-emits either.
    @Test
    fun `dialog over Detail is not auto-tracked and dismiss does not re-emit Detail (action 18)`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val activity = buildHostSettled().get()
        push(activity, S07DetailFragment())
        assertEquals(listOf("S07HomeFragment", "S07DetailFragment"), drainEmittedScreens())

        val dialog = S07DialogFragment()
        dialog.show(activity.supportFragmentManager, "s07-dialog")
        activity.supportFragmentManager.executePendingTransactions()
        // Modal skipped by the resolver -> resolves back to Detail -> deduped -> nothing emitted.
        assertEquals(emptyList<String>(), drainEmittedScreens())

        dialog.dismiss()
        activity.supportFragmentManager.executePendingTransactions()
        // Detail is never re-resumed on dismiss, so nothing re-emits.
        assertEquals(emptyList<String>(), drainEmittedScreens())
    }

    // Action 19: when a transaction sets a primaryNavigationFragment, the resolver prefers it over the
    // reversed-scan candidate (the last-added fragment).
    @Test
    fun `resolver prefers the primary navigation fragment (action 19)`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val activity = buildHostSettled().get()
        assertEquals(listOf("S07HomeFragment"), drainEmittedScreens())

        val primary = S07PrimaryFragment()
        val secondary = S07SecondaryFragment()
        activity.supportFragmentManager.beginTransaction()
            .replace(S07HostActivity.CONTAINER_ID, primary)  // primary added first
            .add(S07HostActivity.CONTAINER_ID, secondary)    // secondary added on top (reversed-scan winner)
            .setPrimaryNavigationFragment(primary)           // but primary wins
            .addToBackStack(null)
            .commit()
        activity.supportFragmentManager.executePendingTransactions()

        assertEquals(listOf("S07PrimaryFragment"), drainEmittedScreens())
    }

    // Action 20: a back press at root Home (empty back stack) exits the Activity — no fragment resumes,
    // so no new screen is emitted.
    @Test
    fun `back press at root exits without emitting a new screen (action 20)`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val activity = buildHostSettled().get()
        assertEquals(listOf("S07HomeFragment"), drainEmittedScreens())

        @Suppress("DEPRECATION")
        activity.onBackPressed() // empty back stack -> finishes the Activity
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(emptyList<String>(), drainEmittedScreens())
        assertTrue("Activity should be finishing after root back press", activity.isFinishing)
    }
}
