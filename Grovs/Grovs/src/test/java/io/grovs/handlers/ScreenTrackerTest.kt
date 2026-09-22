package io.grovs.handlers

import io.grovs.handlers.ScreenTracker.Companion.DEDUP_WINDOW_MS
import io.grovs.utils.InstantCompat
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@ExperimentalCoroutinesApi
class ScreenTrackerTest {

    private lateinit var customEvents: ICustomEventsManager
    private lateinit var tracker: ScreenTracker

    @Before
    fun setUp() {
        customEvents = mockk(relaxed = true)
        tracker = ScreenTracker(customEvents)
    }

    private val start = InstantCompat.now().minusMillis(60_000)

    /** An observation time [windows] dedup windows (plus a millisecond) after [start]. */
    private fun afterWindow(windows: Int = 1): InstantCompat =
        start.plusMillis(windows * (DEDUP_WINDOW_MS + 1))

    @Test
    fun `screen view is emitted as a screen_view event with screen_name`() = runTest {
        tracker.trackScreen("HomeActivity")

        coVerify {
            customEvents.track(
                name = "screen_view",
                properties = mapOf("screen_name" to "HomeActivity"),
                tags = null,
                createdAt = any(),
            )
        }
    }

    @Test
    fun `aliases replace the raw class name`() = runTest {
        tracker.setAliases(mapOf("HomeActivity" to "Home"))

        tracker.trackScreen("HomeActivity")

        coVerify {
            customEvents.track(
                name = "screen_view",
                properties = mapOf("screen_name" to "Home"),
                tags = null,
                createdAt = any(),
            )
        }
    }

    @Test
    fun `caller properties are merged alongside screen_name`() = runTest {
        tracker.trackScreen("Cart", properties = mapOf("items" to 3.0))

        coVerify {
            customEvents.track(
                name = "screen_view",
                properties = mapOf("items" to 3.0, "screen_name" to "Cart"),
                tags = null,
                createdAt = any(),
            )
        }
    }

    @Test
    fun `the same screen within the dedup window fires only once`() = runTest {
        tracker.trackScreen("HomeActivity")
        tracker.trackScreen("HomeActivity")

        coVerify(exactly = 1) { customEvents.track(any(), any(), any(), any()) }
    }

    @Test
    fun `the same screen after the dedup window fires again`() = runTest {
        tracker.trackScreen("HomeActivity", at = start)
        tracker.trackScreen("HomeActivity", at = afterWindow())

        coVerify(exactly = 2) { customEvents.track(any(), any(), any(), any()) }
    }

    @Test
    fun `dedup measures the gap between observations, not between processing`() = runTest {
        val first = InstantCompat.now().minusMillis(10_000)
        val second = first.plusMillis(DEDUP_WINDOW_MS + 1)

        tracker.trackScreen("HomeActivity", at = first)
        tracker.trackScreen("HomeActivity", at = second)

        coVerify(exactly = 2) { customEvents.track(any(), any(), any(), any()) }
    }

    @Test
    fun `two observations close together are deduped even when processed far apart`() = runTest {
        val first = InstantCompat.now().minusMillis(60_000)
        val second = first.plusMillis(DEDUP_WINDOW_MS - 1)

        tracker.trackScreen("HomeActivity", at = first)
        tracker.trackScreen("HomeActivity", at = second)

        coVerify(exactly = 1) { customEvents.track(any(), any(), any(), any()) }
    }

    @Test
    fun `the screen view carries the observation time`() = runTest {
        val observedAt = InstantCompat.now().minusMillis(5_000)

        tracker.trackScreen("HomeActivity", at = observedAt)

        coVerify(exactly = 1) { customEvents.track(any(), any(), any(), observedAt) }
    }

    @Test
    fun `a different screen within the dedup window still fires`() = runTest {
        tracker.trackScreen("HomeActivity")
        tracker.trackScreen("CartFragment")

        coVerify(exactly = 2) { customEvents.track(any(), any(), any(), any()) }
    }

    @Test
    fun `the same visit reported again after the dedup window is suppressed`() = runTest {
        tracker.trackScreen("home", visitId = "entry-1", at = start)
        tracker.trackScreen("home", visitId = "entry-1", at = afterWindow())

        coVerify(exactly = 1) { customEvents.track(any(), any(), any(), any()) }
    }

    @Test
    fun `a new visit of the same screen after the dedup window fires again`() = runTest {
        tracker.trackScreen("home", visitId = "entry-1", at = start)
        tracker.trackScreen("home", visitId = "entry-2", at = afterWindow())

        coVerify(exactly = 2) { customEvents.track(any(), any(), any(), any()) }
    }

    @Test
    fun `returning to an earlier visit after another screen fires again`() = runTest {
        tracker.trackScreen("home", visitId = "entry-1")
        tracker.trackScreen("detail", visitId = "entry-2")
        tracker.trackScreen("home", visitId = "entry-1")

        coVerify(exactly = 3) { customEvents.track(any(), any(), any(), any()) }
    }

    @Test
    fun `a recreated host re-reporting the last screen after the dedup window is suppressed once`() = runTest {
        tracker.trackScreen("Detail", dedupKey = "app.Detail", at = start)
        tracker.expectRecreationOf("app.Detail")
        tracker.trackScreen("Detail", dedupKey = "app.Detail", at = afterWindow(1))
        tracker.trackScreen("Detail", dedupKey = "app.Detail", at = afterWindow(2))

        coVerify(exactly = 2) { customEvents.track(any(), any(), any(), any()) }
    }

    @Test
    fun `a recreation mark for a screen other than the last tracked one does not suppress`() = runTest {
        tracker.trackScreen("Home", dedupKey = "app.Home", at = start)
        tracker.trackScreen("Detail", dedupKey = "app.Detail", at = start)
        tracker.expectRecreationOf("app.Home")
        tracker.trackScreen("Home", dedupKey = "app.Home", at = afterWindow())

        coVerify(exactly = 3) { customEvents.track(any(), any(), any(), any()) }
    }

    @Test
    fun `a recreation mark does not survive a dedup reset`() = runTest {
        tracker.trackScreen("Home", dedupKey = "app.Home")
        tracker.expectRecreationOf("app.Home")
        tracker.resetDedup()
        tracker.trackScreen("Home", dedupKey = "app.Home")

        coVerify(exactly = 2) { customEvents.track(any(), any(), any(), any()) }
    }

    @Test
    fun `the SDK's own notification screens are skipped`() {
        assertTrue(tracker.shouldSkip("NotificationsMainFragment"))
        assertTrue(tracker.shouldSkip("NotificationsListFragment"))
        assertTrue(tracker.shouldSkip("NotificationDetailsFragment"))
        assertTrue(tracker.shouldSkip("AutoDisplayedNotificationFragment"))
    }

    @Test
    fun `consumer screens are not skipped`() {
        assertFalse(tracker.shouldSkip("HomeActivity"))
        assertFalse(tracker.shouldSkip("CheckoutFragment"))
    }

}
