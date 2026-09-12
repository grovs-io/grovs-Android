package io.grovs.e2e

import io.grovs.handlers.GrovsManager
import io.grovs.service.GrovsService
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Virtual time: the launch budget ends at 14s (waits of 2s, 4s, 8s, no jitter) and the first
 * automatic retry waits 10s after that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AuthenticationAutoRetryE2ETest : DrivenBackendTestBase() {

    private val budget = GrovsService.MAX_ATTEMPTS.toInt()

    private fun failLaunchWhileOpen(code: Int = 500) {
        backend.failWithStatus(authenticate, code = code, body = """{"error":"down"}""")
        configure()
        foreground()
        pumpUntil("the launch to send its first attempt") { backend.count(authenticate) >= 1 }
        advanceUntil("the launch budget to be spent") { manager().lastAuthenticationFailure != null }
    }

    @Test
    fun `the SDK logs in on its own while the user stays in the app`() {
        failLaunchWhileOpen()
        assertEquals("the launch budget is spent", budget, backend.count(authenticate))

        backend.respond(authenticate, authOk)
        advanceUntil("the SDK to log in without a foreground") {
            manager().authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED
        }
    }

    @Test
    fun `a foreground inside the window waits it out instead of giving up`() {
        backend.failWithStatus(authenticate, code = 500, body = """{"error":"down"}""")
        configure()
        pumpUntil("the launch to send its first attempt") { backend.count(authenticate) >= 1 }
        advanceUntil("the launch budget to be spent") { manager().lastAuthenticationFailure != null }
        backend.respond(authenticate, authOk)

        foreground()
        assertEquals("nothing is sent inside the window", budget, backend.count(authenticate))

        advanceUntil("the SDK to log in once the window ends") {
            manager().authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED
        }
    }

    @Test
    fun `no automatic retry while the app is in the background`() {
        backend.failWithStatus(authenticate, code = 500, body = """{"error":"down"}""")
        configure()
        pumpUntil("the launch to send its first attempt") { backend.count(authenticate) >= 1 }
        advance(120_000)

        assertEquals("never opened, so no timer", budget, backend.count(authenticate))
    }

    @Test
    fun `going to the background cancels the pending retry`() {
        failLaunchWhileOpen()
        background()
        advance(120_000)

        assertEquals(budget, backend.count(authenticate))
    }

    @Test
    fun `a bad API key is never retried on a timer`() {
        failLaunchWhileOpen(code = 401)
        advance(120_000)

        assertEquals("one request, no timer", 1, backend.count(authenticate))
    }

    @Test
    fun `a foreground while the timer is waiting does not add a second budget`() {
        failLaunchWhileOpen()
        // The user leaves and comes back at once. Leaving cancels the timer; coming back inside the
        // window re-arms it for a full 10s (Robolectric's SystemClock stands still here), so the
        // second budget only starts once that new window ends.
        foreground()
        advanceUntil("the timed retry to spend its budget") { backend.count(authenticate) >= budget * 2 }
        // The next window after the second failure is 20s, well above this span.
        advance(5_000)

        assertEquals("exactly one extra budget", budget * 2, backend.count(authenticate))
    }

    // ---- Dangling timer checks. A timer that fires logs "Automatic authentication retry";
    // ---- DebugLogger writes through android.util.Log, which ShadowLog captures per test.

    private fun timerFirings() = io.grovs.service.loggedCount("Automatic authentication retry")

    @Test
    fun `leaving and returning many times leaves exactly one live timer`() {
        failLaunchWhileOpen()
        // Each return inside the window re-arms the timer. Every re-arm must cancel the one before.
        repeat(5) {
            background()
            foreground()
        }
        advanceUntil("the timer to fire") { timerFirings() >= 1 }
        advance(5_000)

        assertEquals("only the last timer fires", 1, timerFirings())
    }

    @Test
    fun `the timer keeps re-arming until the backend comes back`() {
        failLaunchWhileOpen()
        advanceUntil("three failed cycles") { backend.count(authenticate) >= budget * 3 }

        backend.respond(authenticate, authOk)
        advanceUntil("the SDK to log in on a later cycle") {
            manager().authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED
        }
    }

    @Test
    fun `a failure that lands after the user left arms nothing`() {
        backend.failWithStatus(authenticate, code = 500, body = """{"error":"down"}""")
        configure()
        foreground()
        pumpUntil("the launch to send its first attempt") { backend.count(authenticate) >= 1 }
        // Leaves while the launch budget is still running; the failure lands in the background.
        background()
        advance(120_000)

        assertEquals(budget, backend.count(authenticate))
        assertEquals(0, timerFirings())
    }

    @Test
    fun `a pending timer never fires after the SDK is disabled`() {
        failLaunchWhileOpen()
        io.grovs.Grovs.setSDK(false)
        pump()
        advance(120_000)

        assertEquals(budget, backend.count(authenticate))
        assertEquals(0, timerFirings())
    }

    @Test
    fun `a pending timer never fires for a replaced configuration`() {
        failLaunchWhileOpen()
        backend.respond(authenticate, authOk)
        // A new manager. The pending timer belongs to the old one and must die with it.
        configure()
        pumpUntil("the new configuration to log in") {
            manager().authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED
        }
        advance(120_000)

        assertEquals("only the new launch's single request", budget + 1, backend.count(authenticate))
        assertEquals(0, timerFirings())
    }
}
