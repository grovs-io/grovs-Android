package io.grovs.e2e.analytics

import android.os.Bundle
import android.os.Looper
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.grovs.Grovs
import io.grovs.e2e.E2ETestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * Setup 09 — nested fragments: a container fragment hosting child fragments in its
 * childFragmentManager, optionally two levels deep to grandchildren. Exercises the resolver's
 * recursion and eligibility rules (primaryNavigationFragment, isHidden, userVisibleHint, view
 * visibility, null view) down to the visible leaf.
 */
@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class S09NestedFragmentsE2ETest {

    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        E2ETestUtils.resetGrovsSingleton()
        E2ETestUtils.setupMockGlInfo()
        E2ETestUtils.setupTestApplication(RuntimeEnvironment.getApplication())
        E2ETestUtils.setupMockUserAgent(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
        )
        S09HostActivity.autoAddChildA = false
        mockWebServer = MockWebServer()
        mockWebServer.start()
        E2ETestUtils.enqueueAuthenticationSuccess(mockWebServer)
    }

    @After
    fun tearDown() {
        S09HostActivity.autoAddChildA = false
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

    /** Settles the pending resolution, flushes custom events, and returns any NEW screen_view names. */
    private fun drainScreens(): List<String> {
        settleAutomaticScreenResolution()
        E2ETestUtils.flushCustomEvents()
        return E2ETestUtils.eventsFromBatchRequests(E2ETestUtils.collectAllRequests(mockWebServer))
            .filter { it.optString("event_name") == "screen_view" }
            .map { it.getJSONObject("properties").getString("screen_name") }
    }

    /** Builds the host activity to STARTED (not yet resumed), returns controller + container. */
    private fun startHost(): Pair<ActivityController<S09HostActivity>, S09ContainerFragment> {
        val controller = Robolectric.buildActivity(S09HostActivity::class.java).create().start()
        val activity = controller.get()
        val container = activity.supportFragmentManager
            .findFragmentByTag(S09HostActivity.S09_CONTAINER_TAG) as S09ContainerFragment
        return controller to container
    }

    private fun S09ContainerFragment.childFm() = this.childFragmentManager

    private fun childTag(container: S09ContainerFragment, tag: String) =
        container.childFragmentManager.findFragmentByTag(tag) as S09LeafFragment

    /** Cold start: ChildA is the visible leaf (resolver descends past the container). */
    @Test
    fun `action01 cold start resolves visible leaf child`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val (controller, container) = startHost()
        container.childFm().beginTransaction()
            .add(S09ContainerFragment.CONTENT_ID, S09ChildAFragment(), "childA")
            .commitNow()
        controller.resume()

        assertEquals(listOf("S09ChildAFragment"), drainScreens())
    }

    /** Replace the visible child A -> B -> C in the childFragmentManager. */
    @Test
    fun `action02_03 replacing the visible child emits each new leaf`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val (controller, container) = startHost()
        container.childFm().beginTransaction()
            .add(S09ContainerFragment.CONTENT_ID, S09ChildAFragment(), "childA")
            .commitNow()
        controller.resume()

        val seq = mutableListOf<String>()
        seq += drainScreens() // A

        container.childFm().beginTransaction()
            .replace(S09ContainerFragment.CONTENT_ID, S09ChildBFragment(), "childB")
            .commitNow()
        seq += drainScreens() // B

        container.childFm().beginTransaction()
            .replace(S09ContainerFragment.CONTENT_ID, S09ChildCFragment(), "childC")
            .commitNow()
        seq += drainScreens() // C

        assertEquals(listOf("S09ChildAFragment", "S09ChildBFragment", "S09ChildCFragment"), seq)
    }

    /** Hidden child + visible child -> picks the visible one. */
    @Test
    fun `action04 hidden plus visible child picks the visible child`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val (controller, container) = startHost()
        val hidden = S09ChildBFragment()
        container.childFm().beginTransaction()
            .add(S09ContainerFragment.CONTENT_ID, S09ChildAFragment(), "childA")
            .add(S09ContainerFragment.CONTENT_ID, hidden, "childB")
            .hide(hidden)
            .commitNow()
        controller.resume()

        assertEquals(listOf("S09ChildAFragment"), drainScreens())
    }

    /** All children hidden -> resolver falls back to the container fragment. */
    @Test
    fun `action05 all children hidden falls back to container`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val (controller, container) = startHost()
        val a = S09ChildAFragment()
        val b = S09ChildBFragment()
        container.childFm().beginTransaction()
            .add(S09ContainerFragment.CONTENT_ID, a, "childA")
            .hide(a)
            .add(S09ContainerFragment.CONTENT_ID, b, "childB")
            .hide(b)
            .commitNow()
        controller.resume()

        // No eligible child -> findVisibleLeaf(childFM) == null -> the container becomes the leaf.
        assertEquals(listOf("S09ContainerFragment"), drainScreens())
    }

    /** userVisibleHint=false excludes an otherwise-eligible child -> next eligible chosen. */
    @Test
    fun `action06 userVisibleHint false child is excluded`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val (controller, container) = startHost()
        val offscreen = S09ChildAFragment().apply { userVisibleHint = false }
        container.childFm().beginTransaction()
            .add(S09ContainerFragment.CONTENT_ID, S09ChildBFragment(), "childB")
            .add(S09ContainerFragment.CONTENT_ID, offscreen, "childA")
            .commitNow()
        controller.resume()

        // Reversed scan hits ChildA (uvh=false, skipped) then ChildB (eligible).
        assertEquals(listOf("S09ChildBFragment"), drainScreens())
    }

    /** child.view visibility GONE excludes it -> next eligible chosen. */
    @Test
    fun `action07 gone view child is excluded`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val (controller, container) = startHost()
        val goneChild = S09ChildBFragment()
        container.childFm().beginTransaction()
            .add(S09ContainerFragment.CONTENT_ID, S09ChildAFragment(), "childA")
            .add(S09ContainerFragment.CONTENT_ID, goneChild, "childB")
            .commitNow()
        goneChild.requireView().visibility = View.GONE
        controller.resume()

        assertEquals(listOf("S09ChildAFragment"), drainScreens())
    }

    /** Two levels deep: ChildA hosts GrandchildX -> the grandchild is the leaf. */
    @Test
    fun `action08_19 two levels deep resolves the grandchild leaf`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val (controller, container) = startHost()
        val childA = S09ChildAFragment()
        container.childFm().beginTransaction()
            .add(S09ContainerFragment.CONTENT_ID, childA, "childA")
            .commitNow()
        childA.childFragmentManager.beginTransaction()
            .add(childA.contentId, S09GrandchildXFragment(), "gx")
            .commitNow()
        controller.resume()

        assertEquals(listOf("S09GrandchildXFragment"), drainScreens())
    }

    /** Swap grandchild X -> Y inside the leaf child's childFragmentManager. */
    @Test
    fun `action09 swapping the grandchild emits the new grandchild leaf`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val (controller, container) = startHost()
        val childA = S09ChildAFragment()
        container.childFm().beginTransaction()
            .add(S09ContainerFragment.CONTENT_ID, childA, "childA")
            .commitNow()
        childA.childFragmentManager.beginTransaction()
            .add(childA.contentId, S09GrandchildXFragment(), "gx")
            .commitNow()
        controller.resume()

        val seq = mutableListOf<String>()
        seq += drainScreens() // GX

        childA.childFragmentManager.beginTransaction()
            .replace(childA.contentId, S09GrandchildYFragment(), "gy")
            .commitNow()
        seq += drainScreens() // GY

        assertEquals(listOf("S09GrandchildXFragment", "S09GrandchildYFragment"), seq)
    }

    /** primaryNavigationFragment on the container but NOT on the child -> reversed scan. */
    @Test
    fun `action10 no child primary nav uses reversed scan`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val (controller, container) = startHost()
        // childFragmentManager gets no primaryNavigationFragment; only the activity set one (the container).
        container.childFm().beginTransaction()
            .add(S09ContainerFragment.CONTENT_ID, S09ChildAFragment(), "childA")
            .commitNow()
        controller.resume()

        assertEquals(listOf("S09ChildAFragment"), drainScreens())
    }

    /** primaryNavigationFragment points at a hidden child -> reversed scan picks the visible one. */
    @Test
    fun `action11 hidden primary nav child is ignored`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val (controller, container) = startHost()
        val hiddenPrimary = S09ChildBFragment()
        container.childFm().beginTransaction()
            .add(S09ContainerFragment.CONTENT_ID, S09ChildAFragment(), "childA")
            .add(S09ContainerFragment.CONTENT_ID, hiddenPrimary, "childB")
            .hide(hiddenPrimary)
            .setPrimaryNavigationFragment(hiddenPrimary)
            .commitNow()
        controller.resume()

        // primary (B) is hidden -> not eligible -> reversed scan finds the visible ChildA.
        assertEquals(listOf("S09ChildAFragment"), drainScreens())
    }

    /** Rapid A->B->C child swaps within one looper tick coalesce to the last leaf (C). */
    @Test
    fun `action12 rapid child swaps coalesce to the last leaf`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val (controller, container) = startHost()
        container.childFm().beginTransaction()
            .add(S09ContainerFragment.CONTENT_ID, S09ChildAFragment(), "childA")
            .commitNow()
        controller.resume()
        assertEquals(listOf("S09ChildAFragment"), drainScreens())

        // Three swaps with no settle in between -> only the most recent resolution job survives.
        container.childFm().beginTransaction()
            .replace(S09ContainerFragment.CONTENT_ID, S09ChildBFragment(), "childB").commitNow()
        container.childFm().beginTransaction()
            .replace(S09ContainerFragment.CONTENT_ID, S09ChildCFragment(), "childC2").commitNow()
        container.childFm().beginTransaction()
            .replace(S09ContainerFragment.CONTENT_ID, S09ChildCFragment(), "childC3").commitNow()

        // Last two are the same class; coalesced resolution emits it once.
        assertEquals(listOf("S09ChildCFragment"), drainScreens())
    }

    /** Rotation recreates the container + children; the re-resolved leaf is deduped. */
    @Test
    fun `action13 rotation dedups the re-resolved leaf`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        S09HostActivity.autoAddChildA = true
        val controller = Robolectric.buildActivity(S09HostActivity::class.java).create().start().resume()
        assertEquals(listOf("S09ChildAFragment"), drainScreens())

        controller.recreate()
        // Same leaf name within the 1s dedup window -> suppressed.
        assertEquals(emptyList<String>(), drainScreens())
    }

    /** Process-death restore rebuilds the tree; the visible leaf is restored. */
    @Test
    fun `action14 process death restore recreates the visible leaf`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        S09HostActivity.autoAddChildA = true
        val bundle = Bundle()
        val c1 = Robolectric.buildActivity(S09HostActivity::class.java).create().start().resume()
        assertEquals(listOf("S09ChildAFragment"), drainScreens())
        c1.saveInstanceState(bundle)
        c1.pause().stop().destroy()

        // "Process death": a brand-new activity restored from the saved bundle.
        val c2 = Robolectric.buildActivity(S09HostActivity::class.java).create(bundle).start().resume()
        val restored = drainScreens() // deduped (same name within 1s)

        val container2 = c2.get().supportFragmentManager
            .findFragmentByTag(S09HostActivity.S09_CONTAINER_TAG) as? S09ContainerFragment
        assertNotNull("container should be restored", container2)
        assertTrue(
            "restored container should hold a ChildA leaf",
            container2!!.childFragmentManager.fragments.any { it is S09ChildAFragment }
        )
        // Restored leaf resolves to ChildA but is deduped against the pre-death emission.
        assertEquals(emptyList<String>(), restored)
    }

    /** Add a child on top (reversed scan picks it), then remove it to reveal the child under. */
    @Test
    fun `action15_16 overlap picks top child then reveal misses`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val (controller, container) = startHost()
        container.childFm().beginTransaction()
            .add(S09ContainerFragment.CONTENT_ID, S09ChildAFragment(), "childA")
            .commitNow()
        controller.resume()
        assertEquals(listOf("S09ChildAFragment"), drainScreens())

        // Add ChildB on top of ChildA (both eligible) -> reversed scan picks the last-added (B).
        val top = S09ChildBFragment()
        container.childFm().beginTransaction()
            .add(S09ContainerFragment.CONTENT_ID, top, "childB")
            .commitNow()
        assertEquals(listOf("S09ChildBFragment"), drainScreens())

        // Remove the top child. ChildA was never re-resumed -> no callback -> nothing emitted.
        container.childFm().beginTransaction()
            .remove(top)
            .commitNow()
        assertEquals(emptyList<String>(), drainScreens())
    }

    /** Swap the container at the activity level: A-container -> B-container. */
    @Test
    fun `action17 activity level container swap emits the new leaf`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val (controller, container) = startHost()
        container.childFm().beginTransaction()
            .add(S09ContainerFragment.CONTENT_ID, S09ChildAFragment(), "childA")
            .commitNow()
        controller.resume()
        assertEquals(listOf("S09ChildAFragment"), drainScreens())

        val activity = controller.get()
        val containerB = S09ContainerBFragment()
        activity.supportFragmentManager.beginTransaction()
            .replace(S09HostActivity.ROOT_ID, containerB, "containerB")
            .setPrimaryNavigationFragment(containerB)
            .commitNow()

        assertEquals(listOf("S09ChildOfBFragment"), drainScreens())
    }

    /** A resumed child with no view (onCreateView returned null) is skipped. */
    @Test
    fun `action18 null view child is skipped`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val (controller, container) = startHost()
        container.childFm().beginTransaction()
            .add(S09ContainerFragment.CONTENT_ID, S09ChildAFragment(), "childA")
            .add(S09ContainerFragment.CONTENT_ID, S09NoViewFragment(), "noView")
            .commitNow()
        controller.resume()

        // Reversed scan hits the view-less fragment first (skipped) then ChildA.
        assertEquals(listOf("S09ChildAFragment"), drainScreens())
    }

    /** Navigate the grandchild to the same class again -> leaf then dedup. */
    @Test
    fun `action20 re-navigating grandchild to same class dedups`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val (controller, container) = startHost()
        val childA = S09ChildAFragment()
        container.childFm().beginTransaction()
            .add(S09ContainerFragment.CONTENT_ID, childA, "childA")
            .commitNow()
        childA.childFragmentManager.beginTransaction()
            .add(childA.contentId, S09GrandchildXFragment(), "gx")
            .commitNow()
        controller.resume()
        assertEquals(listOf("S09GrandchildXFragment"), drainScreens())

        // Replace with a new instance of the SAME class -> resolves the same name -> deduped.
        childA.childFragmentManager.beginTransaction()
            .replace(childA.contentId, S09GrandchildXFragment(), "gx2")
            .commitNow()
        assertEquals(emptyList<String>(), drainScreens())
    }
}
