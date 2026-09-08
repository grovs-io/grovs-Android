package io.grovs.viewmodels

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.grovs.model.notifications.Notification
import io.grovs.model.notifications.NotificationsResponse
import io.grovs.service.GrovsService
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSResult
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

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        application = RuntimeEnvironment.getApplication()
        service = mockk(relaxed = true)
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
}
