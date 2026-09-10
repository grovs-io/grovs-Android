package io.grovs.handlers

import android.app.Activity
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import io.grovs.GrovsNotificationsListener
import io.grovs.service.GrovsService
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNull
import kotlinx.coroutines.test.setMain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class NotificationsManagerTest {

    @Test
    fun `automatic notifications can be triggered from a background thread`() {
        // The SDK calls this from its serial dispatcher. lifecycleScope registers a lifecycle
        // observer on first access, and LifecycleRegistry insists that happens on the main thread.
        // An unconfined Main runs that registration inline on the calling thread, which is exactly
        // what happens when the host or a test replaces Main.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
            val manager = NotificationsManager(
                context = RuntimeEnvironment.getApplication(),
                grovsContext = GrovsContext(),
                apiKey = "test-api-key",
                activityProvider = object : ActivityProvider {
                    override fun requireActivity(): Activity = activity
                    override fun requireNotificationsListener(): GrovsNotificationsListener? = null
                },
            )

            // Uncaught coroutine exceptions reach the current thread's handler.
            val failure = AtomicReference<Throwable?>()
            val worker = Thread { manager.displayAutomaticNotificationsIfNeeded() }
            worker.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> failure.set(e) }
            worker.start()
            worker.join()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertNull(failure.get()?.let { it.toString() + "\n" + it.stackTrace.take(5).joinToString("\n") }, failure.get())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `automatic notifications do nothing while disabled`() {
        // Covers the scenario in the B2 findings: an auth job that keeps running after a disable
        // cancels it mid-launch-logging must not reach here and hit the network.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
            val mockGrovsService = mockk<GrovsService>(relaxed = true)
            val activityProvider = mockk<ActivityProvider>(relaxed = true)
            every { activityProvider.requireActivity() } returns activity
            val grovsContext = GrovsContext()
            grovsContext.settings.sdkEnabled = false

            val manager = NotificationsManager(
                context = RuntimeEnvironment.getApplication(),
                grovsContext = grovsContext,
                apiKey = "test-api-key",
                activityProvider = activityProvider,
                grovsService = mockGrovsService,
            )

            manager.displayAutomaticNotificationsIfNeeded()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            coVerify(exactly = 0) { mockGrovsService.notificationsToDisplayAutomatically() }
            // The gate sits before requireActivity(), so a disabled SDK never even asks for one.
            verify(exactly = 0) { activityProvider.requireActivity() }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
