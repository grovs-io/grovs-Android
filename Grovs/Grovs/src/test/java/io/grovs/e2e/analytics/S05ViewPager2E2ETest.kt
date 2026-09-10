package io.grovs.e2e.analytics

import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import io.grovs.Grovs
import io.grovs.e2e.E2ETestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * S05 — ViewPager2 + FragmentStateAdapter (swipeable tabs).
 *
 * FragmentStateAdapter caps every page except the current one at STARTED, so only the visible page
 * reaches RESUMED and is reported; offscreen neighbours never are.
 */
@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class S05ViewPager2E2ETest {

    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        E2ETestUtils.resetGrovsSingleton()
        E2ETestUtils.setupMockGlInfo()
        E2ETestUtils.setupTestApplication(RuntimeEnvironment.getApplication())
        E2ETestUtils.setupMockUserAgent(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
        )
        // Reset fixture config to defaults for each test.
        S05HostActivity.initialItem = 0
        S05HostActivity.offscreenLimit = ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT
        S05HostActivity.page2HostsChild = false
        S05HostActivity.pageCount = 3

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

    // ---- looper / resolution helpers ----

    private fun idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
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

    /** Force the ViewPager2 / inner RecyclerView to measure + lay out so page fragments bind. */
    private fun layoutPager(activity: S05HostActivity) {
        val width = 1080
        val height = 1920
        val pager = activity.pager
        pager.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        pager.layout(0, 0, width, height)
        activity.supportFragmentManager.executePendingTransactions()
        idle()
    }

    /** Cold start: drive the host to RESUMED with the pager laid out, coalescing into one resolution. */
    private fun coldStart(controller: ActivityController<S05HostActivity>): S05HostActivity {
        controller.create().start().visible()
        val activity = controller.get()
        // Lay out while only STARTED so the current page is capped at STARTED, then resume: page
        // STARTED->RESUMED and activity resume land in the same tick and coalesce into one job.
        layoutPager(activity)
        controller.resume()
        activity.supportFragmentManager.executePendingTransactions()
        settleAutomaticScreenResolution()
        return activity
    }

    /** Switch to [index] (host already resumed) and let the resolution settle. */
    private fun selectPage(
        controller: ActivityController<S05HostActivity>,
        index: Int,
        smooth: Boolean = false,
    ) {
        val activity = controller.get()
        activity.pager.setCurrentItem(index, smooth)
        activity.supportFragmentManager.executePendingTransactions()
        layoutPager(activity)
        activity.supportFragmentManager.executePendingTransactions()
        settleAutomaticScreenResolution()
    }

    /** Drains the mock server and returns the ordered screen_name list from custom-event POSTs. */
    private fun collectScreenViews(): List<String> {
        E2ETestUtils.flushCustomEvents()
        val requests = E2ETestUtils.collectAllRequests(mockWebServer)
        return E2ETestUtils.eventsFromBatchRequests(requests)
            .filter { it.optString("event_name") == "screen_view" }
            .map { it.getJSONObject("properties").getString("screen_name") }
    }

    private fun newHost(): ActivityController<S05HostActivity> =
        Robolectric.buildActivity(S05HostActivity::class.java)

    private fun resumedPageFragments(activity: S05HostActivity): List<String> =
        activity.supportFragmentManager.fragments
            .filter { it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
            .map { it.javaClass.simpleName }

    // Cold start on a page emits exactly that page.
    @Test
    fun `action01 cold start on page1 emits page1`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        coldStart(newHost())

        assertEquals(listOf("S05Page1Fragment"), collectScreenViews())
    }

    @Test
    fun `action15 deep link initial page3 emits page3`() = runTest {
        S05HostActivity.initialItem = 2
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        coldStart(newHost())

        assertEquals(listOf("S05Page3Fragment"), collectScreenViews())
    }

    // Sequential swipes emit each current page (a TabLayout click maps to setCurrentItem).
    @Test
    fun `actions02_03_04 sequential swipes emit each current page`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = newHost()
        coldStart(controller)          // page1
        selectPage(controller, 1)      // page2
        selectPage(controller, 2)      // page3
        selectPage(controller, 0)      // page1 again (distinct from previous -> not deduped)

        assertEquals(
            listOf("S05Page1Fragment", "S05Page2Fragment", "S05Page3Fragment", "S05Page1Fragment"),
            collectScreenViews(),
        )
    }

    // Reselecting the current page fires no lifecycle change -> nothing new.
    @Test
    fun `actions05_19 reselecting current page emits nothing`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = newHost()
        coldStart(controller)          // page1
        selectPage(controller, 0)      // reselect page1 -> no change
        selectPage(controller, 1)      // page2
        selectPage(controller, 1)      // reselect page2 -> no change

        assertEquals(
            listOf("S05Page1Fragment", "S05Page2Fragment"),
            collectScreenViews(),
        )
    }

    // Programmatic setCurrentItem (non-smooth and smooth).
    @Test
    fun `actions06_07 programmatic setCurrentItem emits target pages`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = newHost()
        coldStart(controller)                       // page1
        selectPage(controller, 2, smooth = false)   // -> page3
        selectPage(controller, 0, smooth = true)    // -> page1

        assertEquals(
            listOf("S05Page1Fragment", "S05Page3Fragment", "S05Page1Fragment"),
            collectScreenViews(),
        )
    }

    // Rapid page changes in one tick coalesce to only the final RESUMED page.
    @Test
    fun `action08 rapid page changes coalesce to final page only`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = newHost()
        val activity = coldStart(controller)        // page1

        // Two changes with NO settle in between: the second resolution job cancels the first.
        activity.pager.setCurrentItem(1, false)
        activity.pager.setCurrentItem(2, false)
        activity.supportFragmentManager.executePendingTransactions()
        layoutPager(activity)
        activity.supportFragmentManager.executePendingTransactions()
        settleAutomaticScreenResolution()

        val screens = collectScreenViews()
        // page1 (cold start) then page3 (final); the intermediate page2 must not appear.
        assertEquals(listOf("S05Page1Fragment", "S05Page3Fragment"), screens)
        assertFalse("page2 must not be emitted in a coalesced jump", screens.contains("S05Page2Fragment"))
    }

    // Default offscreenPageLimit=1 -> neighbour STARTED, not RESUMED, not tracked.
    @Test
    fun `action09 offscreen neighbour is not tracked`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = newHost()
        val activity = coldStart(controller)        // page1

        // Only the current page is RESUMED even though the neighbour page2 is created/STARTED.
        assertEquals(listOf("S05Page1Fragment"), resumedPageFragments(activity))
        assertEquals(listOf("S05Page1Fragment"), collectScreenViews())
    }

    // offscreenPageLimit=3 -> all pages created, still only current RESUMED/tracked.
    @Test
    fun `action10 all pages created but only current emits`() = runTest {
        S05HostActivity.offscreenLimit = 3
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = newHost()
        val activity = coldStart(controller)        // page1

        // All three pages exist as fragments...
        val all = activity.supportFragmentManager.fragments.map { it.javaClass.simpleName }.toSet()
        assertTrue("all pages should be created with offscreenPageLimit=3", all.size >= 3)
        // ...but only the current one is RESUMED and tracked.
        assertEquals(listOf("S05Page1Fragment"), resumedPageFragments(activity))
        assertEquals(listOf("S05Page1Fragment"), collectScreenViews())
    }

    // A page hosting nested child fragments resolves to the visible child leaf.
    @Test
    fun `action14 page hosting nested child resolves to child leaf`() = runTest {
        S05HostActivity.page2HostsChild = true
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = newHost()
        coldStart(controller)          // page1
        selectPage(controller, 1)      // page2 -> descends into its child

        assertEquals(
            listOf("S05Page1Fragment", "S05Page2ChildFragment"),
            collectScreenViews(),
        )
    }

    // Navigate within a page to a detail, then pop back.
    @Test
    fun `actions16_17 detail push emits detail, pop does not re-emit page`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = newHost()
        val activity = coldStart(controller)   // page1
        selectPage(controller, 1)              // page2

        val page2 = activity.supportFragmentManager.fragments
            .first { it is S05Page2Fragment }
        val container = page2.requireView() as ViewGroup

        // Push a detail into page2's child FM.
        val detail = S05DetailFragment()
        page2.childFragmentManager.beginTransaction()
            .add(container.id, detail, "s05-detail")
            .setPrimaryNavigationFragment(detail)
            .addToBackStack("detail")
            .commit()
        page2.childFragmentManager.executePendingTransactions()
        layoutPager(activity)
        settleAutomaticScreenResolution()

        // Pop the detail: page2 is never re-resumed, so nothing new is emitted.
        page2.childFragmentManager.popBackStack()
        page2.childFragmentManager.executePendingTransactions()
        layoutPager(activity)
        settleAutomaticScreenResolution()

        assertEquals(
            listOf("S05Page1Fragment", "S05Page2Fragment", "S05DetailFragment"),
            collectScreenViews(),
        )
    }

    // Rotation on page2 -> re-resolves the same page, deduped within 1s.
    @Test
    fun `action11 rotation re-resolves same page and dedups`() = runTest {
        S05HostActivity.initialItem = 1
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = newHost()
        coldStart(controller)          // page2
        controller.configurationChange()
        val recreated = controller.get()
        layoutPager(recreated)
        recreated.supportFragmentManager.executePendingTransactions()
        settleAutomaticScreenResolution()

        // Same screen within the 1s dedup window -> only the original emit survives.
        assertEquals(listOf("S05Page2Fragment"), collectScreenViews())
    }

    // Background then foreground on page3.
    @Test
    fun `action12 background then foreground on page3`() = runTest {
        S05HostActivity.initialItem = 2
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = newHost()
        val activity = coldStart(controller)   // page3

        controller.pause().stop()              // background
        idle()
        controller.start().resume()            // foreground
        activity.supportFragmentManager.executePendingTransactions()
        settleAutomaticScreenResolution()

        // Foreground re-resolves page3; identical name within 1s dedups -> only one emit.
        assertEquals(listOf("S05Page3Fragment"), collectScreenViews())
    }

    // Process-death restore on page2 (a relaunch straight onto page2).
    @Test
    fun `action13 restore on page2 emits page2`() = runTest {
        S05HostActivity.initialItem = 1
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        coldStart(newHost())           // relaunched directly on page2

        assertEquals(listOf("S05Page2Fragment"), collectScreenViews())
    }

    // Swapping the adapter must not crash; resolver keeps reporting the current page.
    @Test
    fun `action20 swapping adapter does not crash`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = newHost()
        val activity = coldStart(controller)   // page1

        // Replace the adapter wholesale.
        activity.pager.adapter = S05PagerAdapter(activity)
        activity.supportFragmentManager.executePendingTransactions()
        layoutPager(activity)
        activity.supportFragmentManager.executePendingTransactions()
        settleAutomaticScreenResolution()

        // No crash; the first page is present and only one page is RESUMED.
        assertEquals(listOf("S05Page1Fragment"), resumedPageFragments(activity))
        val screens = collectScreenViews()
        assertTrue("cold start page1 must have been emitted", screens.contains("S05Page1Fragment"))
    }
}

// ---- Fixtures (all prefixed S05) ----

open class S05PageFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply { id = View.generateViewId() }
}

class S05Page1Fragment : S05PageFragment()

class S05Page2Fragment : S05PageFragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (S05HostActivity.page2HostsChild && savedInstanceState == null &&
            childFragmentManager.findFragmentByTag("s05-child") == null
        ) {
            val child = S05Page2ChildFragment()
            childFragmentManager.beginTransaction()
                .add(view.id, child, "s05-child")
                .setPrimaryNavigationFragment(child)
                .commitNow()
        }
    }
}

class S05Page3Fragment : S05PageFragment()

class S05Page2ChildFragment : S05PageFragment()

class S05DetailFragment : S05PageFragment()

class S05PagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = S05HostActivity.pageCount
    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> S05Page1Fragment()
        1 -> S05Page2Fragment()
        else -> S05Page3Fragment()
    }
}

class S05HostActivity : AppCompatActivity() {
    lateinit var pager: ViewPager2

    companion object {
        var initialItem: Int = 0
        var offscreenLimit: Int = ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT
        var page2HostsChild: Boolean = false
        var pageCount: Int = 3
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply { id = View.generateViewId() }
        pager = ViewPager2(this).apply { id = View.generateViewId() }
        root.addView(
            pager,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        setContentView(root)

        pager.adapter = S05PagerAdapter(this)
        if (offscreenLimit >= 1) {
            pager.offscreenPageLimit = offscreenLimit
        }
        if (initialItem != 0) {
            pager.setCurrentItem(initialItem, false)
        }
    }
}
