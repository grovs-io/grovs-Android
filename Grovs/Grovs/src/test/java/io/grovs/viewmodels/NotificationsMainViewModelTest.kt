package io.grovs.viewmodels

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.grovs.model.notifications.Notification
import io.grovs.model.notifications.NotificationsResponse
import io.grovs.service.useConsentController
import io.grovs.service.GrovsService
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSResult
import io.mockk.every
import io.grovs.handlers.GrovsContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Paging behaviour of the messages list. The failed-page case matters most: a dropped error used to
 * leave the spinner up forever and stall every later page load.
 */
@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [28])
class NotificationsMainViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()
    private lateinit var application: Application
    private lateinit var service: GrovsService
    private lateinit var viewModel: NotificationsMainViewModel
    private lateinit var consentContext: GrovsContext

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        application = RuntimeEnvironment.getApplication()
        service = mockk(relaxed = true)
        consentContext = GrovsContext()
        every { service.grovsContext } returns consentContext
        every { service.configuration } answers { consentContext.consent.currentConfiguration }
        viewModel = NotificationsMainViewModel(application)
        viewModel.grovsService = service
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun notification(id: Int) = Notification(
        id = id,
        title = "Message $id",
        updatedAt = InstantCompat.now(),
        subtitle = null,
        autoDisplay = false,
        accessURL = null,
        read = false,
    )

    private fun page(vararg ids: Int) =
        LSResult.Success(NotificationsResponse(notifications = ids.map(::notification)))

    @Test
    fun `a failed page load clears the spinner`() = runTest(mainDispatcher) {
        coEvery { service.notifications(any()) } returns
            LSResult.Error(java.io.IOException("offline"))

        viewModel.loadMoreNotifications()
        advanceUntilIdle()

        assertFalse(
            "isLoading should be reset after a failed page load",
            viewModel.isLoading.value
        )
    }

    @Test
    fun `a failed page load leaves the page cursor where it was so the next call retries it`() =
        runTest(mainDispatcher) {
            coEvery { service.notifications(any()) } returns
                LSResult.Error(java.io.IOException("offline"))

            viewModel.loadMoreNotifications()
            advanceUntilIdle()
            viewModel.loadMoreNotifications()
            advanceUntilIdle()

            coVerify(exactly = 2) { service.notifications(1) }
        }

    @Test
    fun `a successful page load advances the page cursor and appends the results`() =
        runTest(mainDispatcher) {
            coEvery { service.notifications(1) } returns page(1, 2)
            coEvery { service.notifications(2) } returns page(3)

            viewModel.loadMoreNotifications()
            advanceUntilIdle()
            viewModel.loadMoreNotifications()
            advanceUntilIdle()

            coVerify(exactly = 1) { service.notifications(1) }
            coVerify(exactly = 1) { service.notifications(2) }
            assertEquals(listOf(1, 2, 3), viewModel.notifications.value.map { it.id })
            assertFalse(viewModel.isLoading.value)
        }

    @Test
    fun `a second load while one is in flight is ignored`() = runTest(mainDispatcher) {
        val inFlight = CompletableDeferred<Unit>()
        coEvery { service.notifications(any()) } coAnswers {
            inFlight.await()
            page(1)
        }

        // Both calls happen in the same frame, before either coroutine has been dispatched - the
        // case a guard raised inside the coroutine would not catch.
        viewModel.loadMoreNotifications()
        viewModel.loadMoreNotifications()
        runCurrent()

        assertTrue("a load should be in flight", viewModel.isLoading.value)

        inFlight.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { service.notifications(1) }
    }
    @Test
    fun `revocation before dispatch releases loading and a new grant can reload page one`() = runTest(mainDispatcher) {
        coEvery { service.notifications(1) } returns page(1)
        viewModel.loadMoreNotifications()
        consentContext.settings.sdkEnabled = false
        runCurrent()
        assertFalse(viewModel.isLoading.value)
        assertTrue(viewModel.notifications.value.isEmpty())
        coVerify(exactly = 0) { service.notifications(any()) }
        consentContext.settings.sdkEnabled = true
        viewModel.loadMoreNotifications()
        advanceUntilIdle()
        assertEquals(listOf(1), viewModel.notifications.value.map { it.id })
        coVerify(exactly = 1) { service.notifications(1) }
    }

    @Test
    fun `late page after disable-enable cannot advance items or pagination`() = runTest(mainDispatcher) {
        val cleanup = io.grovs.service.GatedExecutor()
        consentContext.useConsentController(io.grovs.handlers.ConsentController(cleanupExecutor = cleanup))
        val gate = CompletableDeferred<Unit>()
        coEvery { service.notifications(1) } coAnswers { gate.await(); page(1) }
        try {
            viewModel.loadMoreNotifications()
            runCurrent()
            assertTrue(viewModel.isLoading.value)
            consentContext.settings.sdkEnabled = false
            consentContext.settings.sdkEnabled = true
            gate.complete(Unit)
            runCurrent()
            assertFalse(viewModel.isLoading.value)
            assertTrue(viewModel.notifications.value.isEmpty())
            coEvery { service.notifications(1) } returns page(2)
            viewModel.loadMoreNotifications()
            runCurrent()
            assertEquals(listOf(2), viewModel.notifications.value.map { it.id })
            coVerify(exactly = 2) { service.notifications(1) }
            coVerify(exactly = 0) { service.notifications(2) }
        } finally { gate.complete(Unit); cleanup.open(); runCurrent() }
    }

    @Test
    fun `both notification view models reject queued mark-read after revocation`() = runTest(mainDispatcher) {
        val auto = AutoDisplayedNotificationViewModel(application).also { it.grovsService = service }
        coEvery { service.notifications(1) } returns page(1)
        coEvery { service.markNotificationAsRead(1) } returns LSResult.Success(true)
        viewModel.loadMoreNotifications()
        runCurrent()
        val item = viewModel.notifications.value.single()
        viewModel.markAsRead(item)
        auto.markAsRead(item)
        consentContext.settings.sdkEnabled = false
        runCurrent()
        assertFalse(item.read)
        coVerify(exactly = 0) { service.markNotificationAsRead(any()) }
        consentContext.settings.sdkEnabled = true
        viewModel.markAsRead(item)
        runCurrent()
        assertTrue(item.read)
        coVerify(exactly = 1) { service.markNotificationAsRead(1) }
    }

    @Test
    fun `late mark-read response cannot change read state under a new grant`() = runTest(mainDispatcher) {
        val cleanup = io.grovs.service.GatedExecutor()
        consentContext.useConsentController(io.grovs.handlers.ConsentController(cleanupExecutor = cleanup))
        val gate = CompletableDeferred<Unit>()
        coEvery { service.notifications(1) } returns page(1)
        coEvery { service.markNotificationAsRead(1) } coAnswers { gate.await(); LSResult.Success(true) }
        try {
            viewModel.loadMoreNotifications()
            runCurrent()
            val item = viewModel.notifications.value.single()
            viewModel.markAsRead(item)
            runCurrent()
            consentContext.settings.sdkEnabled = false
            consentContext.settings.sdkEnabled = true
            gate.complete(Unit)
            runCurrent()
            assertFalse(item.read)
            coEvery { service.markNotificationAsRead(1) } returns LSResult.Success(true)
            viewModel.markAsRead(item)
            runCurrent()
            assertTrue(item.read)
        } finally { gate.complete(Unit); cleanup.open(); runCurrent() }
    }

}
