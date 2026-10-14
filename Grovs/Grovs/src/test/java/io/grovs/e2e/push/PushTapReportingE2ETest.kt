package io.grovs.e2e.push

import android.content.Intent
import android.net.Uri
import io.grovs.Grovs
import io.grovs.GrovsMessaging
import io.grovs.e2e.contract.BackendContract
import io.grovs.e2e.contract.ContractTestBase
import io.grovs.e2e.contract.PushPayloads
import io.grovs.e2e.contract.PushPayloads.tapIntent
import io.grovs.utils.InstantCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
internal class PushTapReportingE2ETest : ContractTestBase() {

    private fun failAuthentication() {
        backend.failWithStatus("authenticate", code = 500, body = """{"error":"down"}""")
        configure()
        pumpUntil("the first authentication attempt") { backend.count("authenticate") >= 1 }
        settle(60_000)
    }

    private fun recoverAuthentication() {
        contract.install()
        contract.linkResolves(PushPayloads.LINK, mapOf("screen" to "link", "only_link" to "yes"))
        backend.phase = "recovered"
        // Skips the auth backoff wait, which has no public trigger.
        manager().authenticationBackoffElapsedMs = { Long.MAX_VALUE }
        foreground(Intent())
    }

    @Test
    fun `P7 a tap posts one push_open with the campaign, the session and the device headers`() {
        authenticated()
        foreground(tapIntent())
        settle(5_000)

        pumpUntil("the open") { pushOpens() >= 1 }
        assertEquals(1, pushOpens())
        val body = contract.bodies("push_open").single()
        assertEquals(PushPayloads.CAMPAIGN, body.getString("campaign_id"))
        assertTrue(body.getString("session_id").isNotBlank())
        val request = backend.seen.single { it.path == "push_open" }.request
        listOf("PROJECT-KEY", "PLATFORM", "IDENTIFIER", "LINKSQUARED").forEach {
            assertFalse("$it missing", BackendContract.header(request, it).isNullOrBlank())
        }
        assertEquals("test-grovs-id", BackendContract.header(request, "LINKSQUARED"))
    }

    @Test
    fun `P7 a tap that starts a new session reports the open with the new session`() {
        authenticated()
        val oldSession = manager().grovsContext.sessionId
        background()
        settle(2_000)
        // The session timeout is measured on the wall clock, which the test cannot move.
        val context = manager().grovsContext
        context.javaClass.getDeclaredField("backgroundedAt").apply { isAccessible = true }
            .set(context, InstantCompat.ofEpochMilli(System.currentTimeMillis() - 31 * 60_000L))
        foreground(tapIntent(PushPayloads.data(link = null)))
        pumpUntil("the open") { pushOpens() >= 1 }

        assertNotEquals("a new session started", oldSession, context.sessionId)
        assertEquals(context.sessionId, contract.bodies("push_open").single().getString("session_id"))
    }

    @Test
    fun `P8 a resolving link delivers the link with push keys winning over link data`() {
        contract.linkResolves(PushPayloads.LINK, mapOf("screen" to "link", "only_link" to "yes"))
        authenticated()
        foreground(tapIntent())
        pumpUntil("delivery") { deepLinks.delivered.isNotEmpty() }

        val details = deepLinks.delivered.single()
        assertEquals(PushPayloads.LINK, details.link)
        assertEquals("push", details.data!!["screen"])
        assertEquals("yes", details.data!!["only_link"])
        assertEquals("spring26", details.data!!["promo"])
        assertEquals(details, Grovs.openedLinkDetails)
        assertTrue("delivered on main", deepLinks.onMain.single())
    }

    @Test
    fun `P9 a push without a link is delivered at once even before authentication`() {
        failAuthentication()
        foreground(tapIntent(PushPayloads.data(link = null)))
        pump()

        val details = deepLinks.delivered.single()
        assertNull(details.link)
        assertEquals("spring26", details.data!!["promo"])
        assertEquals(details, Grovs.openedLinkDetails)
    }

    @Test
    fun `P10 a failing link lookup delivers the push data once with no link and does not retry the link`() {
        backend.failWithStatus("data_for_device_and_url", code = 503, body = "down")
        authenticated()
        foreground(tapIntent())
        advanceUntil("delivery") { deepLinks.delivered.isNotEmpty() }
        val lookups = backend.count("data_for_device_and_url")
        foreground()
        foreground()
        settle(60_000)

        assertNull(deepLinks.delivered.single().link)
        assertEquals("spring26", deepLinks.delivered.single().data!!["promo"])
        assertEquals("no later lookup for that link", lookups, backend.count("data_for_device_and_url"))
    }

    @Test
    fun `P10 a link that does not resolve delivers the push data with no link`() {
        authenticated()
        foreground(tapIntent())
        pumpUntil("delivery") { deepLinks.delivered.isNotEmpty() }
        settle(5_000)

        assertNull(deepLinks.delivered.single().link)
        assertEquals(1, deepLinks.delivered.size)
    }

    @Test
    fun `P11 a link push before authentication resolves after it and keeps its data`() {
        failAuthentication()
        foreground(tapIntent())
        assertTrue(deepLinks.delivered.isEmpty())

        recoverAuthentication()
        pumpUntil("delivery") { deepLinks.delivered.isNotEmpty() }

        assertEquals(PushPayloads.LINK, deepLinks.delivered.single().link)
        assertEquals("spring26", deepLinks.delivered.single().data!!["promo"])
    }

    @Test
    fun `P12 a non-Grovs intent reports nothing`() {
        authenticated()
        foreground(Intent(Intent.ACTION_VIEW, Uri.parse(PushPayloads.LINK)).putExtra("promo", "x"))
        settle(5_000)

        assertEquals(0, pushOpens())
    }

    @Test
    fun `P13 receiving a push in the foreground is not an open`() {
        authenticated()
        assertTrue(GrovsMessaging.handleMessage(application, PushPayloads.remoteMessage()))
        settle(5_000)

        assertEquals(0, pushOpens())
        assertEquals(0, backend.count("data_for_device_and_url"))
        assertTrue(deepLinks.delivered.isEmpty())
    }

    @Test
    fun `P14 handleNotificationOpen from a background thread is safe and true only for a Grovs push`() {
        authenticated()
        var grovs = false
        var foreign = true
        val thread = Thread {
            grovs = Grovs.handleNotificationOpen(tapIntent(PushPayloads.data(link = null)))
            foreign = Grovs.handleNotificationOpen(Intent(Intent.ACTION_VIEW, Uri.parse("https://other.example")))
        }
        thread.start()
        thread.join(5_000)
        settle(5_000)

        pumpUntil("the open and its delivery") { pushOpens() >= 1 && deepLinks.delivered.isNotEmpty() }
        assertTrue(grovs)
        assertFalse(foreign)
        assertEquals(1, pushOpens())
        assertEquals(1, deepLinks.delivered.size)
    }

    @Test
    fun `P15 the push link is looked up by url and never by fingerprint`() {
        contract.linkResolves(PushPayloads.LINK, mapOf("only_link" to "yes"))
        authenticated()
        pumpUntil("the launch lookup") { backend.count("data_for_device") >= 1 }
        settle(2_000)
        val fingerprintLookups = backend.count("data_for_device")
        foreground(tapIntent())
        pumpUntil("delivery") { deepLinks.delivered.isNotEmpty() }
        settle(5_000)

        assertEquals(PushPayloads.LINK, contract.bodies("data_for_device_and_url").single().getString("url"))
        assertEquals(fingerprintLookups, backend.count("data_for_device"))
    }
}
