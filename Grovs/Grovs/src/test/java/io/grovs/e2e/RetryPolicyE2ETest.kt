package io.grovs.e2e

import io.grovs.handlers.GrovsManager
import io.grovs.service.GrovsService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the SDK does when the backend misbehaves. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RetryPolicyE2ETest : DrivenBackendTestBase() {

    @Test
    fun `a JSON 500 is retried and authentication recovers inside the session`() {
        backend.failWithStatusTimes(
            authenticate, times = 2, code = 500,
            body = """{"error":"internal server error"}""", recoveredBody = authOk,
        )
        configure()

        advanceUntil("authentication to recover once the backend heals") {
            manager().authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED
        }

        assertEquals("two failures then a success", 3, backend.count(authenticate))
    }

    @Test
    fun `a 401 is terminal and is never retried`() {
        backend.failWithStatus(authenticate, code = 401, body = """{"error":"invalid api key"}""")
        configure()

        pumpUntil("the authenticate attempt") { backend.count(authenticate) >= 1 }
        advance(60_000)

        assertEquals("a client error is never retried", 1, backend.count(authenticate))
        assertEquals(GrovsManager.AuthenticationState.UNAUTHENTICATED, manager().authenticationState)
    }

    @Test
    fun `a permanently failing transport stops after the budget`() {
        backend.fail(authenticate)
        configure()

        advance(120_000)

        // The executor's own attempt count is exactly MAX_ATTEMPTS (verified separately in
        // GrovsServiceConsentTest's N06b via the executor's failure log). The raw backend hit count
        // can be one higher here: authenticate's first send reuses the connection the preceding
        // device lookup just finished on, and when that reused connection turns out already dead,
        // OkHttp silently resends once on a fresh connection before the executor ever sees a
        // failure. That resend is a transport-level detail the executor cannot observe or count; it
        // never adds a retry decision, wait, or log line, so the bounded-budget guarantee holds.
        val hits = backend.count(authenticate)
        assertTrue(
            "expected $hits to be the budget (${GrovsService.MAX_ATTEMPTS}) or one more from OkHttp's own stale-connection resend",
            hits == GrovsService.MAX_ATTEMPTS.toInt() || hits == GrovsService.MAX_ATTEMPTS.toInt() + 1,
        )
        assertEquals(
            "a spent budget reports UNAUTHENTICATED rather than staying at RETRYING",
            GrovsManager.AuthenticationState.UNAUTHENTICATED,
            manager().authenticationState,
        )
    }

    @Test
    fun `a device lookup that spends its budget still lets authentication run`() {
        // GrovsManager:403 ignores a device lookup failure on purpose. A spent budget must stay
        // non-fatal, which it cannot be if exhaustion throws.
        backend.failWithStatus(device, code = 500, body = """{"error":"down"}""")
        backend.respond(authenticate, authOk)
        configure()

        advanceUntil("authentication despite the failed device lookup") {
            manager().authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED
        }

        assertEquals(GrovsService.MAX_ATTEMPTS.toInt(), backend.count(device))
    }
}
