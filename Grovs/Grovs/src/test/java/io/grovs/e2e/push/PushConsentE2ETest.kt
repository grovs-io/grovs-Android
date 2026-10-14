package io.grovs.e2e.push

import android.content.Intent
import io.grovs.Grovs
import io.grovs.e2e.contract.BackendContract
import io.grovs.e2e.contract.ContractTestBase
import io.grovs.e2e.contract.PushPayloads
import io.grovs.e2e.contract.PushPayloads.tapIntent
import io.grovs.handlers.GrovsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
internal class PushConsentE2ETest : ContractTestBase() {

    private val tap get() = tapIntent(PushPayloads.data(link = null))

    private fun authFails() {
        backend.failWithStatus("authenticate", code = 500, body = """{"error":"down"}""")
        configure()
        pumpUntil("the failed attempt") { backend.count("authenticate") >= 1 }
        advance(60_000)
    }

    private fun authRecovers() {
        contract.install()
        // Skips the auth backoff wait, which has no public trigger.
        manager().authenticationBackoffElapsedMs = { Long.MAX_VALUE }
        foreground(Intent())
        advance(30_000)
    }

    private fun assertAuthenticated() {
        pumpUntil("authentication") { manager().authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED }
        assertEquals(GrovsManager.AuthenticationState.AUTHENTICATED, manager().authenticationState)
    }

    @Test
    fun `P24 a tap while disabled reports and delivers nothing, also after enabling`() {
        configure(enabled = false)
        foreground(tap)
        Grovs.setSDK(true)
        advance(10_000)
        foreground()
        advance(10_000)

        assertAuthenticated()
        assertEquals(0, pushOpens())
        assertTrue(deepLinks.delivered.isEmpty())
    }

    @Test
    fun `P25 withdrawing consent drops a pending open`() {
        authFails()
        foreground(tap)
        Grovs.setSDK(false)
        pump()
        Grovs.setSDK(true)
        authRecovers()

        assertAuthenticated()
        assertEquals(0, pushOpens())
    }

    @Test
    fun `P25 a failure answering after consent is withdrawn is not retried`() {
        val gate = CountDownLatch(1)
        backend.answer("push_open") { gate.await(10, TimeUnit.SECONDS); BackendContract.json(503, "down") }
        authenticated()
        foreground(tap)
        pumpUntil("the open in flight") { pushOpens() >= 1 }
        Grovs.setSDK(false)
        gate.countDown()
        advance(30_000)
        val sent = pushOpens()
        assertEquals("no in-request retry after consent was withdrawn", 1, sent)
        Grovs.setSDK(true)
        contract.install()
        foreground(Intent())
        advance(30_000)

        assertAuthenticated()
        assertEquals("no report after consent came back", sent, pushOpens())
    }

    @Test
    fun `P26 a new configure starts with no pending opens`() {
        authFails()
        foreground(tap)
        contract.install()
        configure()
        foreground(Intent())
        advance(30_000)

        assertAuthenticated()
        assertEquals(0, pushOpens())
    }
}
