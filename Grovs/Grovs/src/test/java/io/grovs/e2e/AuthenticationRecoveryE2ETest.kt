package io.grovs.e2e

import io.grovs.handlers.GrovsManager
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
}
