package io.grovs.e2e

import io.grovs.handlers.GrovsManager
import io.grovs.service.GrovsService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AuthenticationRecoveryE2ETest : DrivenBackendTestBase() {

    @Test
    fun `a foreground re-authenticates after a failed launch`() {
        backend.failWithStatus(authenticate, code = 500, body = """{"error":"down"}""")
        configure()
        pumpUntil("the launch attempts to fail") { backend.count(authenticate) >= 1 }
        advance(60_000)
        val afterLaunch = backend.count(authenticate)

        backend.respond(authenticate, authOk)
        // Past the backoff window the failed launch opened.
        manager().authenticationBackoffElapsedMs = { Long.MAX_VALUE }
        foreground()

        pumpUntil("authentication to recover on foreground") {
            manager().authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED
        }
        assertTrue("a new attempt was made", backend.count(authenticate) > afterLaunch)
    }

    @Test
    fun `a foreground inside the backoff window does not re-authenticate`() {
        backend.failWithStatus(authenticate, code = 500, body = """{"error":"down"}""")
        configure()
        pumpUntil("the launch attempts to fail") { backend.count(authenticate) >= 1 }
        advance(60_000)
        val afterLaunch = backend.count(authenticate)

        assertFalse("the window is open", manager().canAttemptAuthentication())
        foreground()
        pump()

        assertEquals("the backoff window suppressed the attempt", afterLaunch, backend.count(authenticate))
    }

    @Test
    fun `a foreground while the launch authentication is still in flight does not run a second retry budget`() {
        backend.failWithStatus(authenticate, code = 500, body = """{"error":"down"}""")
        configure()
        // The launch authentication (job A) has started but is still retrying against the down
        // backend; a real cold start's first activity onStart lands in exactly this window.
        pumpUntil("the launch to send its first authenticate attempt") { backend.count(authenticate) >= 1 }

        foreground()

        advanceUntil("the launch budget to be spent") { manager().lastAuthenticationFailure != null }
        // A chained second job would only be starting its device lookup at this instant, so give
        // it time to send its first authenticate request. Still inside the 10s window before the
        // automatic retry fires, so a correct SDK sends nothing here.
        advance(5_000)

        assertEquals(
            "one retry budget, not two",
            GrovsService.MAX_ATTEMPTS.toInt(),
            backend.count(authenticate),
        )
    }
}
