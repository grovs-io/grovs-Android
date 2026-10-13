package io.grovs.handlers

import android.content.Intent
import android.net.Uri
import android.os.BadParcelableException
import io.grovs.Grovs
import io.grovs.model.DeeplinkDetails
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GrovsPushPayloadTest {

    private val campaign = mapOf(
        "grovs_campaign_id" to "k9Qx2",
        "grovs_link" to "https://app.grovs.link/promo",
        "grovs_image" to "https://cdn.grovs.io/i.png",
        "promo" to "spring26",
    )

    @Test
    fun `parses campaign id, link, image and data`() {
        val push = GrovsPushPayload.from(campaign, messageId = "m1")!!

        assertEquals("k9Qx2", push.campaignId)
        assertEquals("https://app.grovs.link/promo", push.link)
        assertEquals("https://cdn.grovs.io/i.png", push.image)
        assertEquals("m1", push.messageId)
        assertEquals(campaign, push.data)
    }

    @Test
    fun `a push without grovs_campaign_id is not a Grovs push`() {
        assertNull(GrovsPushPayload.from(mapOf("promo" to "x"), null))
        assertNull(GrovsPushPayload.from(mapOf("grovs_campaign_id" to ""), null))
    }

    @Test
    fun `grovs_notification_id or linksquared alone is not a Grovs push`() {
        assertNull(GrovsPushPayload.from(mapOf("grovs_notification_id" to "12"), null))
        assertNull(GrovsPushPayload.from(mapOf("linksquared" to "true"), null))
    }

    @Test
    fun `a blank grovs_link is ignored`() {
        val push = GrovsPushPayload.from(mapOf("grovs_campaign_id" to "c", "grovs_link" to " "), null)!!
        assertNull(push.link)
    }

    @Test
    fun `FCM transport extras are not in the payload`() {
        val intent = Intent().apply {
            campaign.forEach { (k, v) -> putExtra(k, v) }
            putExtra("google.message_id", "0:123")
            putExtra("google.c.a.e", "1")
            putExtra("gcm.n.title", "t")
            putExtra("from", "1234")
            putExtra("collapse_key", "io.grovs.test")
            putExtra("message_type", "gcm")
        }

        val push = GrovsPushPayload.from(intent)!!

        assertEquals(campaign, push.data)
        assertEquals("0:123", push.messageId)
    }

    @Test
    fun `an SDK built intent carries its message id in the grovs extra`() {
        val intent = Intent().apply {
            putExtra("grovs_campaign_id", "c")
            putExtra(GrovsPushPayload.MESSAGE_ID_EXTRA, "m7")
        }

        val push = GrovsPushPayload.from(intent)!!

        assertEquals("m7", push.messageId)
        assertEquals(mapOf("grovs_campaign_id" to "c"), push.data)
    }

    @Test
    fun `non string extras are skipped and a broken bundle is not a push`() {
        val withLong = Intent().apply {
            putExtra("grovs_campaign_id", "c")
            putExtra("google.sent_time", 1_700_000_000_000L)
            putExtra("count", 3)
        }
        assertEquals(mapOf("grovs_campaign_id" to "c"), GrovsPushPayload.from(withLong)!!.data)

        // A bundle whose parcel cannot be read throws on first access in production.
        val broken = mockk<Intent>()
        every { broken.extras } throws BadParcelableException("bad")
        assertNull(GrovsPushPayload.from(broken))
    }

    @Test
    fun `a link intent without push extras is not a push`() {
        assertNull(GrovsPushPayload.from(Intent(Intent.ACTION_VIEW, Uri.parse("https://app.grovs.link/x"))))
        assertNull(GrovsPushPayload.from(null as Intent?))
    }

    @Test
    fun `push data wins over the link payload`() {
        val push = GrovsPushPayload.from(campaign + ("screen" to "push"), null)!!
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "UNCHECKED_CAST")
        val resolved = DeeplinkDetails(
            link = "https://app.grovs.link/promo",
            data = mapOf("screen" to "link", "only_link" to "yes") as Map<String, Object>,
            tracking = null,
        )

        val details = push.details(resolved)

        assertEquals("https://app.grovs.link/promo", details.link)
        assertEquals("push", details.data!!["screen"])
        assertEquals("yes", details.data!!["only_link"])
        assertEquals("spring26", details.data!!["promo"])
    }

    @Test
    fun `without a resolved link the details carry the push data and no link`() {
        val details = GrovsPushPayload.from(campaign, null)!!.details(null)

        assertNull(details.link)
        assertEquals("k9Qx2", details.data!!["grovs_campaign_id"])
        assertNull(details.tracking)
    }

    @Test
    fun `isGrovsNotification for a map and an intent`() {
        assertTrue(Grovs.isGrovsNotification(campaign))
        assertFalse(Grovs.isGrovsNotification(mapOf("promo" to "x")))
        assertTrue(Grovs.isGrovsNotification(Intent().putExtra("grovs_campaign_id", "c")))
        assertFalse(Grovs.isGrovsNotification(null as Intent?))
    }
}
