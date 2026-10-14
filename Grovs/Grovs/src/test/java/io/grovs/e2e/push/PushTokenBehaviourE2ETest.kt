package io.grovs.e2e.push

import io.grovs.Grovs
import io.grovs.GrovsMessaging
import io.grovs.e2e.contract.ContractBackend
import io.grovs.e2e.contract.ContractTestBase
import io.grovs.handlers.GrovsManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
internal class PushTokenBehaviourE2ETest : ContractTestBase() {

    private fun attributeBodies(): List<JSONObject> = contract.bodies("visitor_attributes")

    private fun serverIdentity() {
        contract.authBody = ContractBackend.authenticateBody(
            identifier = "user-1",
            attributes = mapOf("plan" to "pro"),
        )
    }

    @Test
    fun `P38 a token set before configure reaches the server`() {
        Grovs.pushToken = "fcm-before"
        authenticated()
        pumpUntil("attributes sent") { attributeBodies().isNotEmpty() }

        assertEquals("fcm-before", attributeBodies().first().getString("push_token"))
    }

    @Test
    fun `P39 onNewToken sends the new token`() {
        authenticated()
        GrovsMessaging.onNewToken("fcm-refreshed")
        pumpUntil("attributes sent") { attributeBodies().any { it.optString("push_token") == "fcm-refreshed" } }
    }

    @Test
    fun `P40 a token before authentication keeps the server identity`() {
        serverIdentity()
        Grovs.pushToken = "fcm-token"
        authenticated()
        pumpUntil("attributes sent") { attributeBodies().isNotEmpty() }

        val body = attributeBodies().first()
        assertEquals("fcm-token", body.getString("push_token"))
        assertEquals("user-1", body.getString("sdk_identifier"))
        assertEquals("pro", body.getJSONObject("sdk_attributes").getString("plan"))
    }

    @Test
    fun `P40 every attributes body carries the identity, since the server overwrites it`() {
        serverIdentity()
        authenticated()
        GrovsMessaging.onNewToken("t1")
        pumpUntil("t1 sent") { attributeBodies().any { it.optString("push_token") == "t1" } }
        GrovsMessaging.onNewToken("t2")
        pumpUntil("t2 sent") { attributeBodies().any { it.optString("push_token") == "t2" } }

        val bodies = attributeBodies()
        assertTrue(bodies.isNotEmpty())
        bodies.forEach {
            assertEquals("user-1", it.optString("sdk_identifier"))
            assertEquals("pro", it.getJSONObject("sdk_attributes").getString("plan"))
        }
    }

    @Test
    fun `P41 host identity set before authentication wins over the server's`() {
        serverIdentity()
        configure()
        Grovs.identifier = "host-user"
        Grovs.attributes = mapOf("plan" to "free")
        Grovs.pushToken = "fcm-token"
        foreground()
        pumpUntil("authentication") { manager().authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED }
        pumpUntil("attributes sent") { attributeBodies().isNotEmpty() }

        val body = attributeBodies().last()
        assertEquals("host-user", body.getString("sdk_identifier"))
        assertEquals("free", body.getJSONObject("sdk_attributes").getString("plan"))
    }

    @Test
    fun `P42 push_open cannot be tracked by the host`() {
        authenticated()
        Grovs.track("push_open")
        Grovs.track("checkout_done")
        advanceUntil("the allowed event to be sent") {
            contract.batchEvents().any { it.optString("event_name") == "checkout_done" }
        }

        assertFalse(contract.batchEvents().any { it.optString("event_name") == "push_open" })
        assertEquals(0, pushOpens())
    }

    @Test
    fun `P42 every name the backend reserves cannot be tracked by the host`() {
        val newlyReserved = listOf("in_app_held", "push_delivered", "push_held", "journey_entered", "journey_held", "journey_path")
        authenticated()
        newlyReserved.forEach { Grovs.track(it) }
        Grovs.track("checkout_done")
        advanceUntil("the allowed event to be sent") {
            contract.batchEvents().any { it.optString("event_name") == "checkout_done" }
        }

        val sent = contract.batchEvents().map { it.optString("event_name") }
        assertTrue("reserved names reached the backend: $sent", sent.none { it in newlyReserved })
    }
}
