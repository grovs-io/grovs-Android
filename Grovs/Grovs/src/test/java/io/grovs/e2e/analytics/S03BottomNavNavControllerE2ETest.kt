package io.grovs.e2e.analytics

import android.content.Intent
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.grovs.e2e.E2ETestUtils
import io.grovs.e2e.ScreenTrackingTestBase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Setup S03 — BottomNavigationView + NavController (multi back stack tabs), AUTO tracking path.
 *
 * Tab selection is simulated with the exact nav-options NavigationUI.setupWithNavController applies
 * for multi-back-stack tabs (launchSingleTop + restoreState + popUpTo(start){saveState}). Real
 * Fragment destinations drive onFragmentResumed callbacks -> VisibleFragmentResolver -> leaf
 * simpleName -> dedup. Each @Test starts from a clean SDK singleton, so the 1s dedup window and the
 * coalescing of pending resolution jobs are observed per-scenario.
 */
@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class S03BottomNavNavControllerE2ETest : ScreenTrackingTestBase() {

    private suspend fun launch(startDest: String? = null): ActivityController<S03HostActivity> {
        configure(true)
        E2ETestUtils.getAuthenticationJob()?.join()
        val intent = Intent(RuntimeEnvironment.getApplication(), S03HostActivity::class.java)
        if (startDest != null) intent.putExtra(S03HostActivity.EXTRA_START_DEST, startDest)
        return Robolectric.buildActivity(S03HostActivity::class.java, intent).setup()
    }

    /** The nav-options NavigationUI applies when a bottom-nav item is tapped (multi back stack). */
    private fun selectTab(nav: NavController, route: String) {
        nav.navigate(
            route,
            NavOptions.Builder()
                .setLaunchSingleTop(true)
                .setRestoreState(true)
                .setPopUpTo(nav.graph.startDestinationId, /* inclusive = */ false, /* saveState = */ true)
                .build(),
        )
    }

    // Actions 1-5: cold start, tab switching, reselect-current (dedup).
    @Test
    fun action01to05_tabSwitchingAndReselect() = runTest {
        val c = launch()
        val nav = c.get().navController

        assertEquals(listOf("S03HomeFragment"), drainScreenNames())                 // 1 cold start on Home
        selectTab(nav, S03Routes.SEARCH)
        assertEquals(listOf("S03SearchFragment"), drainScreenNames())               // 2 switch to Search
        selectTab(nav, S03Routes.PROFILE)
        assertEquals(listOf("S03ProfileFragment"), drainScreenNames())              // 3 switch to Profile
        selectTab(nav, S03Routes.HOME)
        assertEquals(listOf("S03HomeFragment"), drainScreenNames())                 // 4 return to Home
        selectTab(nav, S03Routes.HOME)
        assertEquals(emptyList<String>(), drainScreenNames())                       // 5 reselect current = dedup no-op
    }

    // Actions 6-9: Detail under Home, saveState on tab-away, restoreState on return, back to root.
    @Test
    fun action06to09_detailSaveStateRestoreState() = runTest {
        val c = launch()
        val nav = c.get().navController
        assertEquals(listOf("S03HomeFragment"), drainScreenNames())                 // cold Home

        nav.navigate(S03Routes.DETAIL)
        assertEquals(listOf("S03DetailFragment"), drainScreenNames())               // 6 Home -> Detail
        selectTab(nav, S03Routes.SEARCH)
        assertEquals(listOf("S03SearchFragment"), drainScreenNames())               // 7 switch to Search (Home stack saveState)
        selectTab(nav, S03Routes.HOME)
        // 8 restoreState restores the saved [Home, Detail] stack -> restored leaf (Detail) re-resumes.
        assertEquals(listOf("S03DetailFragment"), drainScreenNames())
        nav.popBackStack()
        assertEquals(listOf("S03HomeFragment"), drainScreenNames())                 // 9 back from Detail to Home root
    }

    // Action 10: system back on a secondary tab returns to the fixed start (Home).
    @Test
    fun action10_systemBackFromSecondaryTabReturnsHome() = runTest {
        val c = launch()
        val nav = c.get().navController
        assertEquals(listOf("S03HomeFragment"), drainScreenNames())
        selectTab(nav, S03Routes.SEARCH)
        assertEquals(listOf("S03SearchFragment"), drainScreenNames())
        // Back stack under Search is [Home, Search]; back pops Search, re-resuming Home.
        nav.popBackStack()
        assertEquals(listOf("S03HomeFragment"), drainScreenNames())                 // 10
    }

    // Action 11: deep link straight into the Search tab.
    @Test
    fun action11_deepLinkIntoSearchTab() = runTest {
        val c = launch(startDest = S03Routes.SEARCH)
        assertEquals(listOf("S03SearchFragment"), drainScreenNames())               // 11
        c.pause().stop().destroy()
    }

    // Action 12: deep link straight into Detail (under Home).
    @Test
    fun action12_deepLinkIntoDetailUnderHome() = runTest {
        val c = launch(startDest = S03Routes.DETAIL)
        assertEquals(listOf("S03DetailFragment"), drainScreenNames())               // 12
        c.pause().stop().destroy()
    }

    // Action 13: rapid tab switch in one tick coalesces to the final destination.
    @Test
    fun action13_rapidTabSwitchCoalescesToFinal() = runTest {
        val c = launch()
        val nav = c.get().navController
        assertEquals(listOf("S03HomeFragment"), drainScreenNames())                 // cold Home baseline

        // No settle between the two selections: intermediate Search job cancelled -> only Profile.
        selectTab(nav, S03Routes.SEARCH)
        selectTab(nav, S03Routes.PROFILE)
        assertEquals(listOf("S03ProfileFragment"), drainScreenNames())              // 13
    }

    // Action 14: rotation (config change) on Profile -> deduped (same screen within 1s).
    @Test
    fun action14_rotationOnProfileDeduped() = runTest {
        val c = launch()
        val nav = c.get().navController
        drainScreenNames()
        selectTab(nav, S03Routes.PROFILE)
        assertEquals(listOf("S03ProfileFragment"), drainScreenNames())
        c.recreate()
        assertEquals(emptyList<String>(), drainScreenNames())                       // 14 dedup
    }

    // Action 15: background then foreground on Search -> deduped (same session, within 1s).
    @Test
    fun action15_backgroundForegroundOnSearchDeduped() = runTest {
        val c = launch()
        val nav = c.get().navController
        drainScreenNames()
        selectTab(nav, S03Routes.SEARCH)
        assertEquals(listOf("S03SearchFragment"), drainScreenNames())
        c.pause().stop()
        assertEquals(emptyList<String>(), drainScreenNames())                       // backgrounded: pending job cancelled
        c.start().resume()
        // Session not rotated within the test window, so dedup is not reset -> Search suppressed.
        assertEquals(emptyList<String>(), drainScreenNames())                       // 15
    }

    // Action 16: process death + restore on Profile, modeled as a fresh configure landing on Profile.
    @Test
    fun action16_processDeathRestoreOnProfile() = runTest {
        val c = launch(startDest = S03Routes.PROFILE)
        assertEquals(listOf("S03ProfileFragment"), drainScreenNames())              // 16
        c.pause().stop().destroy()
    }

    // Action 17: reselecting the current tab pops its stack to root. This models the app-level
    // OnItemReselectedListener (popBackStack(startDest, false)), not setupWithNavController's navigate.
    @Test
    fun action17_reselectTabPopsToRoot() = runTest {
        val c = launch()
        val nav = c.get().navController
        drainScreenNames()
        nav.navigate(S03Routes.DETAIL)
        assertEquals(listOf("S03DetailFragment"), drainScreenNames())
        nav.popBackStack(nav.graph.startDestinationId, /* inclusive = */ false)
        assertEquals(listOf("S03HomeFragment"), drainScreenNames())                 // 17 pops Detail -> Home root
    }

    // Action 18: navigate to the same Detail twice -> second is deduped.
    @Test
    fun action18_navigateSameDetailTwiceDeduped() = runTest {
        val c = launch()
        val nav = c.get().navController
        drainScreenNames()
        nav.navigate(S03Routes.DETAIL)
        assertEquals(listOf("S03DetailFragment"), drainScreenNames())               // 18a
        nav.navigate(S03Routes.DETAIL, NavOptions.Builder().setLaunchSingleTop(true).build())
        assertEquals(emptyList<String>(), drainScreenNames())                       // 18b dedup
    }

    // Action 19: two tabs whose fragments share the SAME simpleName but are DIFFERENT classes. Dedup
    // keys on the fully-qualified class name, so the real cross-tab switch is emitted.
    @Test
    fun action19_sameNameTabsDifferentClassesBothEmit() = runTest {
        configure(true)
        E2ETestUtils.getAuthenticationJob()?.join()
        val c = Robolectric.buildActivity(S03CollisionHostActivity::class.java).setup()
        val nav = c.get().navController

        assertEquals(listOf("S03DupTabFragment"), drainScreenNames())               // 19a cold on TAB_A
        nav.navigate(
            S03Routes.TAB_B,
            NavOptions.Builder().setLaunchSingleTop(true).setRestoreState(true)
                .setPopUpTo(nav.graph.startDestinationId, false, true).build(),
        )
        // TAB_B is a different class sharing the simpleName; dedup keys on the FQN, so it emits.
        // Both report the simpleName as screen_name.
        assertEquals(listOf("S03DupTabFragment"), drainScreenNames())               // 19b real switch emits
        c.pause().stop().destroy()
    }

    // Action 20: programmatic navigate to Profile then immediately back to Home.
    @Test
    fun action20_navigateProfileThenBackToHome() = runTest {
        val c = launch()
        val nav = c.get().navController
        drainScreenNames()
        nav.navigate(S03Routes.PROFILE)
        assertEquals(listOf("S03ProfileFragment"), drainScreenNames())              // 20a
        nav.popBackStack()
        assertEquals(listOf("S03HomeFragment"), drainScreenNames())                 // 20b
    }
}
