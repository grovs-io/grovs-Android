package io.grovs.e2e

import android.os.Looper
import io.grovs.handlers.GrovsManager
import io.grovs.service.GrovsService
import io.grovs.viewmodels.NotificationsMainViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationsTerminationE2ETest : DrivenBackendTestBase() {

    @Test
    fun `a notifications page against a down backend stops loading`() {
        backend.respond(authenticate, authOk)
        configure()
        pumpUntil("authentication") {
            manager().authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED
        }

        backend.failWithStatus(notifications, code = 500, body = """{"error":"down"}""")
        // configure() already pointed the context at the backend, and GrovsService builds its
        // Retrofit instance in init, so the base URL is in place before this line.
        val viewModel = NotificationsMainViewModel(application).also {
            it.grovsService = GrovsService(
                context = application,
                apiKey = "test-api-key",
                grovsContext = context,
            )
        }
        viewModel.loadMoreNotifications()

        // The retry waits sit on the main looper's clock, so move that clock rather than the
        // dispatcher's. 60s is comfortably past the whole 2s + 4s + 8s budget.
        var waited = 0L
        while (waited < 60_000 && viewModel.isLoading.value) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
            driver.runCurrent()
            Thread.sleep(5)
            waited += 1_000
        }

        assertFalse("the spinner stops", viewModel.isLoading.value)
        assertEquals(
            "the request stops after the budget rather than spinning forever",
            GrovsService.MAX_ATTEMPTS.toInt(),
            backend.count(notifications),
        )
    }
}
