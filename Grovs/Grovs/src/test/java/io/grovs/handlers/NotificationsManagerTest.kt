package io.grovs.handlers

import android.app.Activity
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import io.grovs.GrovsNotificationsListener
import io.grovs.service.useConsentController
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
            val service = mockk<GrovsService>(relaxed = true)
            io.mockk.coEvery { service.notificationsToDisplayAutomatically() } returns
                io.grovs.utils.LSResult.Success(io.grovs.model.notifications.NotificationsResponse(emptyList()))
            val manager = NotificationsManager(
                context = RuntimeEnvironment.getApplication(),
                grovsContext = GrovsContext(),
                apiKey = "test-api-key",
                activityProvider = object : ActivityProvider {
                    override fun requireActivity(): Activity = activity
                    override fun requireNotificationsListener(): GrovsNotificationsListener? = null
                },
                grovsService = service,
            )

            // Uncaught coroutine exceptions reach the current thread's handler.
            val failure = AtomicReference<Throwable?>()
            val worker = Thread { manager.displayAutomaticNotificationsIfNeeded() }
            worker.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> failure.set(e) }
            worker.start()
            worker.join()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            coVerify(exactly = 1) { service.notificationsToDisplayAutomatically() }
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
    @Test
    fun `automatic notification queued on main keeps its original consent token`() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val context = GrovsContext()
        val service = mockk<GrovsService>(relaxed = true)
        io.mockk.coEvery { service.notificationsToDisplayAutomatically() } returns
            io.grovs.utils.LSResult.Success(io.grovs.model.notifications.NotificationsResponse(emptyList()))
        val provider = mockk<ActivityProvider>(relaxed = true)
        every { provider.requireActivity() } returns activity.get()
        val manager = NotificationsManager(activity.get(), context, "key", provider, service)
        try {
            val worker = Thread { manager.displayAutomaticNotificationsIfNeeded() }
            worker.start()
            worker.join(1_000)
            org.junit.Assert.assertFalse(worker.isAlive)
            context.settings.sdkEnabled = false
            context.settings.sdkEnabled = true
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            coVerify(exactly = 0) { service.notificationsToDisplayAutomatically() }
            manager.displayAutomaticNotificationsIfNeeded()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            coVerify(exactly = 1) { service.notificationsToDisplayAutomatically() }
            activity.saveInstanceState(android.os.Bundle())
            org.junit.Assert.assertFalse(manager.displayNotificationsViewController(null))
        } finally {
            context.consent.retireConfiguration(false)
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun `late automatic notification response cannot show a dialog after re-enable`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val cleanup = io.grovs.service.GatedExecutor()
        val context = GrovsContext().also { it.useConsentController(ConsentController(cleanupExecutor = cleanup)) }
        val service = mockk<GrovsService>(relaxed = true)
        val provider = mockk<ActivityProvider>(relaxed = true)
        every { provider.requireActivity() } returns activity.get()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val notification = io.grovs.model.notifications.Notification(7, "Message", io.grovs.utils.InstantCompat.now(), null, true, null, false)
        io.mockk.coEvery { service.notificationsToDisplayAutomatically() } coAnswers {
            gate.await()
            io.grovs.utils.LSResult.Success(io.grovs.model.notifications.NotificationsResponse(listOf(notification)))
        }
        val manager = NotificationsManager(activity.get(), context, "key", provider, service)
        try {
            manager.displayAutomaticNotificationsIfNeeded()
            runCurrent()
            coVerify(exactly = 1) { service.notificationsToDisplayAutomatically() }
            context.settings.sdkEnabled = false
            context.settings.sdkEnabled = true
            gate.complete(Unit)
            runCurrent()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            org.junit.Assert.assertTrue(activity.get().supportFragmentManager.fragments.isEmpty())
        } finally {
            gate.complete(Unit)
            cleanup.open()
            context.consent.retireConfiguration(false)
            activity.pause().stop().destroy()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

}
