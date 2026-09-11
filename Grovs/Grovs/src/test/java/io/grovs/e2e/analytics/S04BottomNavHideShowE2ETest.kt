package io.grovs.e2e.analytics

import android.content.Intent
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.grovs.Grovs
import io.grovs.e2e.E2ETestUtils
import io.grovs.e2e.ScreenTrackingTestBase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Setup 04 — BottomNavigationView + hide()/show() fragment transactions (LEGACY, the SDK's known gap).
 *
 * A hide/show swap causes no lifecycle transition (all tab fragments stay RESUMED) and there is no
 * NavController, so onFragmentResumed never fires and the switch emits nothing. These tests document
 * that gap, and contrast it with the paths that do fire a resume:
 *   - setMaxLifecycle(RESUMED/STARTED) tab swaps (actions 11-14)
 *   - manual Grovs.trackScreenView(...) escape hatch (action 15)
 *   - a forced Activity re-resume (action 20)
 *   - cold start, rotation/recreate, foreground, real add() (1, 7, 8, 9, 16, 19)
 */
@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class S04BottomNavHideShowE2ETest : ScreenTrackingTestBase() {

    // Harness.

    private fun startIntent(startTab: String? = null): Intent {
        val i = Intent(RuntimeEnvironment.getApplication(), S04HostActivity::class.java)
        if (startTab != null) i.putExtra(S04HostActivity.EXTRA_START_TAB, startTab)
        return i
    }

    private val HOME = "S04HomeFragment"
    private val SEARCH = "S04SearchFragment"
    private val PROFILE = "S04ProfileFragment"
    private val DETAIL = "S04DetailFragment"

    // Tests.

    /** Action 1: cold start with Home shown -> initial resume picks the shown primary-nav tab. */
    @Test
    fun `action 1 - cold start emits the initially shown tab`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        Robolectric.buildActivity(S04HostActivity::class.java, startIntent()).create().start().resume()

        assertEquals(listOf(HOME), drainScreenNames())
    }

    /**
     * Actions 2, 3, 4, 6, 17: hide/show tab switches fire no onFragmentResumed -> nothing emitted
     * beyond the cold-start Home (the gap). Covers single switches, a rapid 3-tab sweep, and a
     * double-show to the same tab.
     */
    @Test
    fun `actions 2 3 4 6 17 - hide-show tab switches emit nothing (GAP)`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val activity = Robolectric.buildActivity(S04HostActivity::class.java, startIntent())
            .create().start().resume().get()
        // Settle now to pin the cold-start Home: the resolution job runs lazily against current state.
        settleAutomaticScreenResolution()

        // 2: Home -> Search
        activity.switchTo(S04HostActivity.TAG_SEARCH)
        // 3: Search -> Profile
        activity.switchTo(S04HostActivity.TAG_PROFILE)
        // 4: Profile -> Home
        activity.switchTo(S04HostActivity.TAG_HOME)
        // 6: rapid switch across all three tabs
        activity.switchTo(S04HostActivity.TAG_SEARCH)
        activity.switchTo(S04HostActivity.TAG_PROFILE)
        activity.switchTo(S04HostActivity.TAG_HOME)
        // 17: double hide/show to the SAME tab (Search twice in a row)
        activity.switchTo(S04HostActivity.TAG_SEARCH)
        activity.switchTo(S04HostActivity.TAG_SEARCH)

        // ONLY the cold-start Home ever emitted. Every hide/show switch above produced nothing.
        assertEquals(listOf(HOME), drainScreenNames())
    }

    /** Action 5: reselecting the already-shown tab has no lifecycle change and nothing to emit (not a gap). */
    @Test
    fun `action 5 - reselecting the current tab emits nothing`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val activity = Robolectric.buildActivity(S04HostActivity::class.java, startIntent())
            .create().start().resume().get()
        settleAutomaticScreenResolution()
        // Reselect Home (already shown).
        activity.switchTo(S04HostActivity.TAG_HOME)

        assertEquals(listOf(HOME), drainScreenNames())
    }

    /**
     * Actions 7 & 18: rotation/recreate resumes the Activity, which re-resolves against the restored
     * hidden-state and surfaces the shown tab (Search). A hide/show after the recreate is still a gap.
     */
    @Test
    fun `actions 7 18 - rotation surfaces the current tab but later hide-show is still a gap`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = Robolectric.buildActivity(S04HostActivity::class.java, startIntent())
            .create().start().resume()
        // Pin the cold-start Home first.
        settleAutomaticScreenResolution()
        // Switch to Search via hide/show (gap: emits nothing).
        controller.get().switchTo(S04HostActivity.TAG_SEARCH)
        settleAutomaticScreenResolution()

        // Action 7: rotate / recreate — the recreated activity resumes and surfaces the shown tab.
        controller.recreate()
        settleAutomaticScreenResolution()

        // Action 18: a hide/show AFTER the recreate is still a gap.
        controller.get().switchTo(S04HostActivity.TAG_PROFILE)

        assertEquals(listOf(HOME, SEARCH), drainScreenNames())
    }

    /**
     * Action 8: background then foreground on Search. Foregrounding drives onActivityResumed, which
     * re-resolves and surfaces Search — a partial mitigation for the gap.
     */
    @Test
    fun `action 8 - background then foreground surfaces the current tab`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = Robolectric.buildActivity(S04HostActivity::class.java, startIntent())
            .create().start().resume()
        settleAutomaticScreenResolution()
        controller.get().switchTo(S04HostActivity.TAG_SEARCH)
        settleAutomaticScreenResolution()

        // Background then foreground.
        controller.pause().stop()
        controller.start().resume()

        assertEquals(listOf(HOME, SEARCH), drainScreenNames())
    }

    /**
     * Actions 9 & 10:
     *  - 9: opening a detail fragment via add()+addToBackStack (a REAL resume) → [S04DetailFragment].
     *  - 10: popping the detail back to Home → nothing (Home is never re-resumed) → GAP.
     * Sequence: [Home (cold), Detail (real resume)]; the pop adds nothing.
     */
    @Test
    fun `actions 9 10 - real add resumes detail but pop back to home is a gap`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val activity = Robolectric.buildActivity(S04HostActivity::class.java, startIntent())
            .create().start().resume().get()
        settleAutomaticScreenResolution()

        // 9: open detail (real resume).
        activity.openDetail()
        settleAutomaticScreenResolution()

        // 10: pop detail — Home is revealed but not re-resumed, so nothing emits.
        activity.popDetail()

        assertEquals(listOf(HOME, DETAIL), drainScreenNames())
    }

    /**
     * Actions 11-14: setMaxLifecycle-based tab switches auto-track, because raising a fragment to
     * RESUMED fires onFragmentResumed. Home->Search->Profile->Home each emit.
     */
    @Test
    fun `actions 11-14 - setMaxLifecycle switches auto-track`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val activity = Robolectric.buildActivity(S04MaxLifecycleHostActivity::class.java)
            .create().start().resume().get()
        settleAutomaticScreenResolution()

        // 12: Home -> Search
        activity.switchTo(S04MaxLifecycleHostActivity.TAG_SEARCH)
        settleAutomaticScreenResolution()
        // 13: Search -> Profile
        activity.switchTo(S04MaxLifecycleHostActivity.TAG_PROFILE)
        settleAutomaticScreenResolution()
        // 14: Profile -> Home
        activity.switchTo(S04MaxLifecycleHostActivity.TAG_HOME)

        assertEquals(listOf(HOME, SEARCH, PROFILE, HOME), drainScreenNames())
    }

    /**
     * Action 15: the documented ESCAPE HATCH. After a hide/show switch (which auto-tracks nothing),
     * calling Grovs.trackScreenView(tabName) emits the tab explicitly. Sequence: [Home (cold), Search].
     */
    @Test
    fun `action 15 - manual trackScreenView after hide-show works`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val activity = Robolectric.buildActivity(S04HostActivity::class.java, startIntent())
            .create().start().resume().get()
        settleAutomaticScreenResolution()

        // hide/show switch: emits nothing on its own...
        activity.switchTo(S04HostActivity.TAG_SEARCH)
        // ...so the app calls the manual API as a workaround.
        Grovs.trackScreenView(SEARCH)

        assertEquals(listOf(HOME, SEARCH), drainScreenNames())
    }

    /**
     * Action 16: deep link that pre-selects the Search tab at launch (Search shown+primary from the
     * first resume). The cold-start resume resolves the shown tab → [S04SearchFragment].
     */
    @Test
    fun `action 16 - deep link initial tab is surfaced on cold start`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        Robolectric.buildActivity(S04HostActivity::class.java, startIntent(S04HostActivity.TAG_SEARCH))
            .create().start().resume()

        assertEquals(listOf(SEARCH), drainScreenNames())
    }

    /**
     * Action 19: process-death restore, simulated by saving instance state, destroying the Activity,
     * and recreating from the saved Bundle. Restored fragments keep their hidden-state and the resume
     * surfaces the visible tab. Robolectric can't truly kill the process (the Grovs singleton survives),
     * so this exercises Android state-restore, not a real cold process.
     */
    @Test
    fun `action 19 - process death restore surfaces the restored tab`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = Robolectric.buildActivity(S04HostActivity::class.java, startIntent())
            .create().start().resume()
        settleAutomaticScreenResolution()
        controller.get().switchTo(S04HostActivity.TAG_SEARCH)
        settleAutomaticScreenResolution()

        val state = Bundle()
        controller.saveInstanceState(state).pause().stop().destroy()

        Robolectric.buildActivity(S04HostActivity::class.java, startIntent())
            .create(state).start().resume()

        assertEquals(listOf(HOME, SEARCH), drainScreenNames())
    }

    /**
     * Action 20: after a hide/show switch, force an Activity re-resume (dispatchActivityResumed). This
     * re-resolves and picks the shown tab, showing the resolver is correct — it is just never
     * re-invoked on a plain hide/show.
     */
    @Test
    fun `action 20 - forced activity re-resume re-resolves the shown tab`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val activity = Robolectric.buildActivity(S04HostActivity::class.java, startIntent())
            .create().start().resume().get()
        settleAutomaticScreenResolution()

        activity.switchTo(S04HostActivity.TAG_SEARCH)
        settleAutomaticScreenResolution()

        // Force a re-resume through the SDK's own lifecycle hook.
        E2ETestUtils.dispatchActivityResumed(activity)

        assertEquals(listOf(HOME, SEARCH), drainScreenNames())
    }
}
