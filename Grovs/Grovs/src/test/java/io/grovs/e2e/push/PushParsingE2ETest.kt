package io.grovs.e2e.push

import android.content.Intent
import io.grovs.Grovs
import io.grovs.e2e.contract.ContractTestBase
import io.grovs.e2e.contract.PushPayloads
import io.grovs.e2e.contract.PushPayloads.tapIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
internal class PushParsingE2ETest : ContractTestBase() {

    @Before
    fun linkResolves() = contract.linkResolves(PushPayloads.LINK, mapOf("from_link" to "yes"))

    private fun tapAfterAuth(intent: Intent) {
        authenticated()
        foreground(intent)
        settle(5_000)
    }

    private fun tapAfterAuthAndOpen(intent: Intent) {
        tapAfterAuth(intent)
        pumpUntil("the open") { pushOpens() >= 1 }
    }

    @Test
    fun `P1 a non-empty grovs_campaign_id makes a Grovs push and an empty or missing one does not`() {
        assertTrue(Grovs.isGrovsNotification(PushPayloads.data()))
        assertFalse(Grovs.isGrovsNotification(PushPayloads.data(campaign = "")))
        assertFalse(Grovs.isGrovsNotification(mapOf("promo" to "spring26")))

        tapAfterAuth(tapIntent(PushPayloads.data(campaign = "")))
        assertEquals(0, pushOpens())
    }

    @Ignore("F-1: a whitespace-only grovs_campaign_id is treated as a Grovs push")
    @Test
    fun `P1 a whitespace-only grovs_campaign_id is not a Grovs push`() {
        assertFalse(Grovs.isGrovsNotification(PushPayloads.data(campaign = "   ")))
    }

    @Test
    fun `P2 legacy inbox keys alone are not a Grovs push`() {
        val legacy = mapOf("linksquared" to "abc", "grovs_notification_id" to "12")
        assertFalse(Grovs.isGrovsNotification(legacy))

        tapAfterAuth(Intent().apply { legacy.forEach { (k, v) -> putExtra(k, v) }; putExtra("google.message_id", "0:legacy") })
        assertEquals(0, pushOpens())
    }

    @Test
    fun `P2 legacy keys next to grovs_campaign_id are delivered as plain data`() {
        tapAfterAuthAndOpen(tapIntent(PushPayloads.data(link = null, custom = mapOf("linksquared" to "abc", "grovs_notification_id" to "12"))))

        assertEquals(1, pushOpens())
        val data = deepLinks.delivered.single().data!!
        assertEquals("abc", data["linksquared"])
        assertEquals("12", data["grovs_notification_id"])
    }

    @Test
    fun `P3 a blank grovs_link is treated as no link`() {
        tapAfterAuthAndOpen(tapIntent(PushPayloads.data(link = "")))

        assertNull(deepLinks.delivered.single().link)
        assertEquals("no link lookup", 0, backend.count("data_for_device_and_url"))
        assertEquals(1, pushOpens())
    }

    @Test
    fun `P4 FCM extras never reach the delivered data and grovs and customer keys do`() {
        tapAfterAuthAndOpen(tapIntent(PushPayloads.data(link = null)).apply { putExtra("io.grovs.message_id", "x"); putExtra("message_type", "gcm") })

        val keys = deepLinks.delivered.single().data!!.keys
        val leaked = keys.filter {
            it.startsWith("google.") || it.startsWith("gcm.") || it.startsWith("io.grovs.") ||
                it in setOf("from", "collapse_key", "message_type")
        }
        assertTrue("leaked $leaked", leaked.isEmpty())
        assertTrue(keys.containsAll(listOf("grovs_campaign_id", "promo", "screen")))
    }

    @Test
    fun `P5 values arrive as the strings the backend sent, including JSON text and empty strings`() {
        val custom = mapOf("n" to 1, "flag" to true, "list" to listOf(1, 2), "obj" to mapOf("a" to 1), "empty" to "")
        tapAfterAuthAndOpen(tapIntent(PushPayloads.data(link = null, custom = custom)))

        val data = deepLinks.delivered.single().data!!
        assertEquals("1", data["n"])
        assertEquals("true", data["flag"])
        assertEquals("[1,2]", data["list"])
        assertEquals("""{"a":1}""", data["obj"])
        assertEquals("", data["empty"])
    }

    @Test
    fun `P6 isGrovsNotification on an intent follows the data rule`() {
        assertTrue(Grovs.isGrovsNotification(tapIntent()))
        assertFalse(Grovs.isGrovsNotification(tapIntent(PushPayloads.data(campaign = ""))))
        assertFalse(Grovs.isGrovsNotification(Intent()))
        assertFalse(Grovs.isGrovsNotification(null as Intent?))
    }
}
