package io.grovs.e2e.push

import android.content.Intent
import android.net.Uri
import io.grovs.Grovs
import io.grovs.e2e.contract.ContractTestBase
import io.grovs.handlers.GrovsManager
import io.grovs.e2e.contract.PushPayloads
import io.grovs.e2e.contract.PushPayloads.tapIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
internal class PushHoldingE2ETest : ContractTestBase() {

    private fun noLink(campaign: String = PushPayloads.CAMPAIGN, messageId: String = "0:m1") =
        tapIntent(PushPayloads.data(campaign = campaign, link = null), messageId)

    @Test
    fun `P16 a tap before configure is replayed by an enabled configure`() {
        assertTrue(Grovs.handleNotificationOpen(noLink()))
        authenticated()
        settle(5_000)

        pumpUntil("the open") { pushOpens() >= 1 }
        assertEquals(1, pushOpens())
        assertEquals(1, deepLinks.delivered.size)
    }

    @Test
    fun `P16 only the last tap before configure is kept`() {
        contract.knownPushCampaigns = setOf("first", "second")
        Grovs.handleNotificationOpen(noLink("first", "0:a"))
        Grovs.handleNotificationOpen(noLink("second", "0:b"))
        authenticated()
        settle(5_000)

        pumpUntil("the open") { pushOpens() >= 1 }
        assertEquals(listOf("second"), contract.bodies("push_open").map { it.getString("campaign_id") })
    }

    @Test
    fun `P16 a tap before a disabled configure is dropped`() {
        Grovs.handleNotificationOpen(noLink())
        configure(enabled = false)
        Grovs.setSDK(true)
        foreground()
        settle(10_000)

        pumpUntil("authentication") { manager().authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED }
        assertEquals(GrovsManager.AuthenticationState.AUTHENTICATED, manager().authenticationState)
        assertEquals(0, pushOpens())
        assertTrue(deepLinks.delivered.isEmpty())
    }

    @Test
    fun `P17 a non-Grovs intent before configure is not held`() {
        assertFalse(Grovs.handleNotificationOpen(Intent(Intent.ACTION_VIEW, Uri.parse(PushPayloads.LINK))))
        authenticated()
        settle(5_000)

        assertEquals(0, backend.count("data_for_device_and_url"))
        assertEquals(0, pushOpens())
    }

    @Test
    fun `P18 a tap before authentication is reported once authenticated`() {
        val gate = backend.hold("authenticate", io.grovs.e2e.contract.ContractBackend.authenticateBody())
        configure()
        foreground(noLink())
        pump()
        assertEquals(0, pushOpens())

        gate.countDown()
        pumpUntil("the open") { pushOpens() >= 1 }
        settle(5_000)
        assertEquals(1, pushOpens())
    }

    @Test
    fun `P18 a tap during an in-flight authentication is reported once`() {
        val gate = backend.hold("authenticate", io.grovs.e2e.contract.ContractBackend.authenticateBody())
        configure()
        foreground()
        pumpUntil("authentication in flight") { backend.count("authenticate") >= 1 }
        foreground(noLink())
        gate.countDown()
        pumpUntil("the open") { pushOpens() >= 1 }
        settle(10_000)

        assertEquals(1, pushOpens())
        assertEquals(1, deepLinks.delivered.size)
    }

    @Test
    fun `P19 a held open survives a failed authentication`() {
        backend.failWithStatus("authenticate", code = 500, body = """{"error":"down"}""")
        configure()
        foreground(noLink())
        pumpUntil("the failed attempt") { backend.count("authenticate") >= 1 }
        settle(60_000)
        assertEquals(0, pushOpens())

        contract.install()
        // Skips the auth backoff wait, which has no public trigger.
        manager().authenticationBackoffElapsedMs = { Long.MAX_VALUE }
        foreground(Intent())
        pumpUntil("the open") { pushOpens() >= 1 }
        settle(5_000)
        assertEquals(1, pushOpens())
    }

    @Test
    fun `P20 repeated taps of one campaign while pending send one open`() {
        val gate = backend.hold("authenticate", io.grovs.e2e.contract.ContractBackend.authenticateBody())
        configure()
        foreground(noLink(messageId = "0:a"))
        foreground(noLink(messageId = "0:b"))
        foreground(noLink(messageId = "0:c"))
        gate.countDown()
        pumpUntil("the open") { pushOpens() >= 1 }
        settle(10_000)

        assertEquals(1, pushOpens())
    }
}
