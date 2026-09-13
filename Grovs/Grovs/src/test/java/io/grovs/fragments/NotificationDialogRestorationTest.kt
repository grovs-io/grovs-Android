package io.grovs.fragments

import android.app.Activity
import android.os.Bundle
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import io.grovs.GrovsNotificationsListener
import io.grovs.e2e.E2ETestUtils
import io.grovs.handlers.ActivityProvider
import io.grovs.handlers.GrovsContext
import io.grovs.handlers.NotificationsManager
import io.grovs.model.notifications.Notification
import io.grovs.model.notifications.NotificationsResponse
import io.grovs.service.GrovsService
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Android rebuilds every fragment a FragmentManager had saved when it recreates an Activity,
 * using the no-argument constructor. The SDK dialogs used to take the service through their
 * constructor, so a rotation or a process restart with one of them open crashed the host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class NotificationDialogRestorationTest {

    private val grovsContext = GrovsContext()
    private val service = mockk<GrovsService>(relaxed = true)
    private val listener = mockk<GrovsNotificationsListener>(relaxed = true)
    private lateinit var controller: ActivityController<FragmentActivity>
    private val notification = Notification(7, "Message", InstantCompat.now(), null, true, "example.com", false)

    @Before
    fun setUp() {
        E2ETestUtils.resetGrovsSingleton()
        every { service.grovsContext } returns grovsContext
        every { service.configuration } returns grovsContext.consent.currentConfiguration
        coEvery { service.notifications(any()) } returns LSResult.Success(NotificationsResponse(emptyList()))
        coEvery { service.notificationsToDisplayAutomatically() } returns
            LSResult.Success(NotificationsResponse(listOf(notification)))
        controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
    }

    @After
    fun tearDown() {
        grovsContext.consent.retireConfiguration(false)
        if (!controller.get().isDestroyed) controller.pause().stop().destroy()
    }

    private fun manager(activity: FragmentActivity) = NotificationsManager(
        context = RuntimeEnvironment.getApplication(),
        grovsContext = grovsContext,
        apiKey = "test-api-key",
        activityProvider = object : ActivityProvider {
            override fun requireActivity(): Activity = activity
            override fun requireNotificationsListener(): GrovsNotificationsListener = listener
        },
        grovsService = service,
    )

    private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `messages dialog survives activity recreation`() {
        assertTrue(manager(controller.get()).displayNotificationsViewController(null))
        idle()

        controller.recreate()
        idle()

        val restored = controller.get().supportFragmentManager.findFragmentByTag("NotificationsMainFragment")
        assertTrue(restored is NotificationsMainFragment && restored.isAdded)
        assertTrue(restored?.let { (it as NotificationsMainFragment).requireDialog().isShowing } == true)
    }

    @Test
    fun `automatic message dialog survives activity recreation and still reports its close`() {
        manager(controller.get()).displayAutomaticNotificationsIfNeeded()
        idle()
        assertNotNull(controller.get().supportFragmentManager.findFragmentByTag("7"))

        controller.recreate()
        idle()

        val restored = controller.get().supportFragmentManager.findFragmentByTag("7") as AutoDisplayedNotificationFragment
        assertTrue(restored.requireDialog().isShowing)

        restored.requireDialog().cancel()
        idle()

        verify(exactly = 1) { listener.onAutomaticNotificationClosed(true) }
    }

    @Test
    fun `messages dialog restored after process death without a configured SDK dismisses itself`() {
        assertTrue(manager(controller.get()).displayNotificationsViewController(null))
        idle()
        val state = Bundle()
        controller.saveInstanceState(state)
        controller.pause().stop().destroy()

        controller = Robolectric.buildActivity(FragmentActivity::class.java).create(state).start().resume()
        idle()
        controller.get().supportFragmentManager.executePendingTransactions()

        assertNull(controller.get().supportFragmentManager.findFragmentByTag("NotificationsMainFragment"))
    }
}
