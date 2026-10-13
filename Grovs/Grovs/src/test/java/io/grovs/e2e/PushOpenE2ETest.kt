package io.grovs.e2e

import android.content.Intent
import android.net.Uri
import io.grovs.Grovs
import io.grovs.GrovsMessaging
import io.grovs.handlers.GrovsPushContent
import io.grovs.handlers.GrovsManager
import io.grovs.model.DeeplinkDetails
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PushOpenE2ETest : DrivenBackendTestBase() {

    private val pushOpen = "push_open"
    private val link = "https://demo.sqd.link/promo"
    private val delivered = mutableListOf<DeeplinkDetails>()

    @Before
    fun listen() {
        backend.respond(pushOpen, """{"message":"Recorded"}""")
        Grovs.setOnDeeplinkReceivedListener(null) { delivered += it }
    }

    private fun pushTap(withLink: Boolean = true, messageId: String = "0:m1") = Intent().apply {
        putExtra("grovs_campaign_id", "k9Qx2")
        putExtra("promo", "spring26")
        putExtra("screen", "push")
        putExtra("google.message_id", messageId)
        if (withLink) putExtra("grovs_link", link)
    }

    private fun linkResolves() =
        backend.respond(payload, """{"link":"$link","data":{"screen":"link","only_link":"yes"}}""")

    private fun authFailsThenTap(intent: Intent) {
        backend.failWithStatus(authenticate, code = 500, body = """{"error":"down"}""")
        configure()
        pumpUntil("the launch attempt to fail") { backend.count(authenticate) >= 1 }
        advance(60_000)
        foreground(intent)
    }

    private fun authRecovers() {
        backend.respond(authenticate, authOk)
        backend.phase = "recovered"
        manager().authenticationBackoffElapsedMs = { Long.MAX_VALUE }
        foreground(Intent())
    }

    private fun pushOpenBody() = JSONObject(backend.seen.last { it.path == pushOpen }.body)

    // ---- Mirrors of ios PushNotificationTests ----

    @Test
    fun `a tapped push reports its campaign with the session`() {
        backend.respond(authenticate, authOk)
        linkResolves()
        configure()
        foreground(pushTap())

        pumpUntil("the open to be reported") { backend.count(pushOpen) >= 1 }
        pumpUntil("the push to be delivered") { delivered.isNotEmpty() }
        assertEquals("k9Qx2", pushOpenBody().getString("campaign_id"))
        assertTrue(pushOpenBody().getString("session_id").isNotEmpty())
    }

    @Test
    fun `a push link resolves through data_for_device_and_url and push data wins over the link payload`() {
        backend.respond(authenticate, authOk)
        linkResolves()
        configure()
        foreground(pushTap())

        pumpUntil("the push to be delivered") { delivered.isNotEmpty() }
        assertEquals(link, JSONObject(backend.seen.last { it.path == payload }.body).getString("url"))
        val details = delivered.single()
        assertEquals(link, details.link)
        assertEquals("push", details.data!!["screen"])
        assertEquals("yes", details.data!!["only_link"])
        assertEquals("spring26", details.data!!["promo"])
        assertEquals(details, Grovs.openedLinkDetails)
    }

    @Test
    fun `a push without a link delivers its data with a null link right away`() {
        authFailsThenTap(pushTap(withLink = false))
        pump()

        val details = delivered.single()
        assertNull(details.link)
        assertEquals("spring26", details.data!!["promo"])
        assertEquals("delivered before authentication", details, Grovs.openedLinkDetails)
        assertEquals("no link lookup for a push without a link", 0, backend.count(payload))
    }

    @Test
    fun `push data is delivered when the link does not resolve, and the link is not parked for retry`() {
        backend.respond(authenticate, authOk)
        backend.failWithStatus(payload, code = 503, body = "no available server")
        configure()
        foreground(pushTap())

        advanceUntil("the push data to be delivered") { delivered.isNotEmpty() }
        pump()

        assertNull(delivered.single().link)
        assertEquals("spring26", delivered.single().data!!["promo"])
        assertNull("not kept for a retry", E2ETestUtils.getPendingLinkRetry()!!.slot)
    }

    @Test
    fun `receiving a foreground push is not an open`() {
        backend.respond(authenticate, authOk)
        configure()
        foreground()
        pumpUntil("authentication") { backend.count(authenticate) >= 1 }
        pump()

        val handled = GrovsMessaging.handlePush(
            application, mapOf("grovs_campaign_id" to "k9Qx2", "grovs_link" to link), "0:fg",
            GrovsPushContent("T", "B", null, null),
        ) { null }
        advance(5_000)

        assertTrue(handled)
        assertEquals(0, backend.count(pushOpen))
        assertEquals(0, backend.count(payload))
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `a non-Grovs intent reports nothing and is handled as before`() {
        backend.respond(authenticate, authOk)
        linkResolves()
        configure()
        foreground(Intent(Intent.ACTION_VIEW, Uri.parse(link)).putExtra("grovs_notification_id", "12"))

        pumpUntil("the link to be delivered") { delivered.isNotEmpty() }
        assertEquals(0, backend.count(pushOpen))
        assertEquals(link, delivered.single().link)
    }

    @Test
    fun `a tap while disabled reports and delivers nothing, and is not picked up after setSDK true`() {
        backend.respond(authenticate, authOk)
        linkResolves()
        configure(enabled = false)
        foreground(pushTap())

        Grovs.setSDK(true)
        backend.phase = "enabled-later"
        advance(5_000)
        foreground()

        assertEquals(0, backend.count(pushOpen))
        assertEquals(0, backend.count(payload))
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `a link push before authentication keeps its data`() {
        linkResolves()
        authFailsThenTap(pushTap())
        assertTrue("nothing delivered while unauthenticated", delivered.isEmpty())

        authRecovers()
        pumpUntil("the parked push to be delivered") { delivered.isNotEmpty() }

        assertEquals(link, delivered.single().link)
        assertEquals("spring26", delivered.single().data!!["promo"])
    }

    @Test
    fun `an open before authentication is sent once authenticated, surviving a failed authentication`() {
        linkResolves()
        authFailsThenTap(pushTap())
        assertEquals(0, backend.count(pushOpen))

        authRecovers()
        pumpUntil("the held open to be sent") { backend.count(pushOpen) >= 1 }
        pump()

        assertEquals("the parked replay does not report again", 1, backend.count(pushOpen))
    }

    @Test
    fun `a failed open is retried on the next foreground`() {
        backend.respond(authenticate, authOk)
        backend.failWithStatus(pushOpen, code = 503, body = "down")
        linkResolves()
        configure()
        foreground(pushTap())
        advanceUntil("the first report to spend its budget") { backend.count(pushOpen) >= ATTEMPTS_PER_REPORT }
        advance(30_000)

        backend.respond(pushOpen, """{"message":"Recorded"}""")
        backend.phase = "recovered"
        foreground()

        pumpUntil("the retry to go out") { backend.count(pushOpen, "recovered") >= 1 }
        assertEquals(1, backend.count(pushOpen, "recovered"))
    }

    @Test
    fun `a failing open is dropped after three attempts`() {
        backend.respond(authenticate, authOk)
        backend.failWithStatus(pushOpen, code = 503, body = "down")
        linkResolves()
        configure()
        foreground(pushTap())
        repeat(3) {
            advance(60_000)
            // A fresh activity each time: restarting the same one stops reaching the process lifecycle.
            foreground(Intent())
        }
        advance(60_000)

        assertEquals(3 * ATTEMPTS_PER_REPORT, backend.count(pushOpen))
    }

    @Test
    fun `withdrawing consent drops pending opens`() {
        linkResolves()
        authFailsThenTap(pushTap())

        Grovs.setSDK(false)
        pump()
        Grovs.setSDK(true)
        backend.phase = "re-enabled"
        authRecovers()
        advance(30_000)

        assertEquals(0, backend.count(pushOpen))
    }

    // ---- Startup slot (before configure) ----

    @Test
    fun `an open before configure is held until configure`() {
        backend.respond(authenticate, authOk)
        linkResolves()

        assertTrue(Grovs.handleNotificationOpen(pushTap()))
        configure()
        foreground()

        pumpUntil("the held tap to be reported") { backend.count(pushOpen) >= 1 }
        pumpUntil("the held tap to be delivered") { delivered.isNotEmpty() }
        assertEquals(1, backend.count(pushOpen))
    }

    @Test
    fun `a tap held before a consent gated configure is dropped`() {
        backend.respond(authenticate, authOk)
        linkResolves()

        Grovs.handleNotificationOpen(pushTap())
        configure(enabled = false)
        Grovs.setSDK(true)
        advance(10_000)
        foreground()

        assertEquals(0, backend.count(pushOpen))
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `a foreign push is not stashed before configure`() {
        backend.respond(authenticate, authOk)
        linkResolves()

        assertFalse(Grovs.handleNotificationOpen(Intent(Intent.ACTION_VIEW, Uri.parse(link))))
        configure()
        foreground()
        advance(5_000)

        assertEquals(0, backend.count(payload))
    }

    // ---- Android only ----

    @Test
    fun `onStart again with the same launcher intent does not report or deliver twice`() {
        backend.respond(authenticate, authOk)
        linkResolves()
        configure()
        foreground(pushTap())
        pumpUntil("the push to be delivered") { delivered.isNotEmpty() }

        foreground()
        foreground()
        advance(5_000)

        assertEquals(1, delivered.size)
        assertEquals(1, backend.count(pushOpen))
    }

    @Test
    fun `a new intent object with a handled message id is ignored`() {
        backend.respond(authenticate, authOk)
        linkResolves()
        configure()
        foreground(pushTap(messageId = "0:same"))
        pumpUntil("the push to be delivered") { delivered.isNotEmpty() }

        foreground(pushTap(messageId = "0:same"))
        advance(5_000)

        assertEquals(1, delivered.size)
        assertEquals(1, backend.count(pushOpen))
    }

    @Test
    fun `onNewIntent then onStart with the same push delivers once`() {
        backend.respond(authenticate, authOk)
        linkResolves()
        configure()
        foreground()
        advance(5_000)

        val tap = pushTap()
        launcher().intent = tap
        Grovs.onNewIntent(tap, launcher())
        foreground()
        pumpUntil("the push to be delivered") { delivered.isNotEmpty() }
        advance(5_000)

        assertEquals(1, delivered.size)
        assertEquals(1, backend.count(pushOpen))
    }

    @Test
    fun `the same tap seen again before it is delivered reports one open`() {
        backend.respond(authenticate, authOk)
        backend.failWithStatus(payload, code = 503, body = "no available server")
        configure()
        foreground()
        pumpUntil("authentication") { manager().authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED }

        val tap = pushTap()
        launcher().intent = tap
        Grovs.onNewIntent(tap, launcher())
        pumpUntil("the open to be reported") { backend.count(pushOpen) >= 1 }
        Grovs.onStart(launcher())
        advanceUntil("the push data to be delivered") { delivered.isNotEmpty() }
        advance(5_000)

        assertEquals(1, backend.count(pushOpen))
        assertEquals(1, delivered.size)
    }

    @Test
    fun `two intent objects carrying the same link push deliver once while the lookup is in flight`() {
        backend.respond(authenticate, authOk)
        configure()
        foreground()
        pumpUntil("authentication") { manager().authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED }
        backend.failWithStatus(payload, code = 503, body = "no available server")

        assertTrue(Grovs.handleNotificationOpen(pushTap(messageId = "0:dup")))
        foreground(pushTap(messageId = "0:dup"))
        advanceUntil("the push data to be delivered") { delivered.isNotEmpty() }
        advance(5_000)

        assertEquals("payload=${backend.count(payload)} open=${backend.count(pushOpen)}", 1, delivered.size)
    }

    private companion object {
        val ATTEMPTS_PER_REPORT = io.grovs.service.GrovsService.MAX_ATTEMPTS.toInt()
    }
}
