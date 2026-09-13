package io.grovs.e2e

import io.grovs.handlers.GrovsManager
import io.grovs.handlers.RequestFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AuthenticationFailureKindE2ETest : DrivenBackendTestBase() {

    @Test
    fun `a bad API key is rejected and gets no automatic retry`() {
        backend.failWithStatus(authenticate, code = 401, body = """{"error":"invalid api key"}""")
        configure()
        pumpUntil("the launch to record its failure") { manager().lastAuthenticationFailure != null }

        assertEquals(RequestFailure.REJECTED, manager().lastAuthenticationFailure)
        assertNull("a bad key never heals on its own", manager().automaticRetryDelayMs())
    }

    @Test
    fun `a server error is retryable`() {
        backend.failWithStatus(authenticate, code = 500, body = """{"error":"down"}""")
        configure()
        pumpUntil("the launch to send its first attempt") { backend.count(authenticate) >= 1 }
        advanceUntil("the launch budget to be spent") { manager().lastAuthenticationFailure != null }

        assertEquals(RequestFailure.RETRYABLE, manager().lastAuthenticationFailure)
        assertNotNull(manager().automaticRetryDelayMs())
    }

    @Test
    fun `offline failures keep the base window and server failures widen it`() {
        backend.respond(authenticate, authOk)
        configure()
        pumpUntil("the launch to log in") {
            manager().authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED
        }
        manager().authenticationBackoffElapsedMs = { 0L }

        repeat(3) { manager().recordAuthenticationOutcome(RequestFailure.OFFLINE) }
        assertEquals("being offline is not the backend's fault", 10_000L, manager().automaticRetryDelayMs())

        repeat(3) { manager().recordAuthenticationOutcome(RequestFailure.RETRYABLE) }
        assertEquals("10s, 20s, 40s", 40_000L, manager().automaticRetryDelayMs())

        manager().recordAuthenticationOutcome(null)
        assertNull("a success clears everything", manager().automaticRetryDelayMs())
        assertNull(manager().lastAuthenticationFailure)
    }
}
