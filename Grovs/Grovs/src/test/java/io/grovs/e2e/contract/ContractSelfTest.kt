package io.grovs.e2e.contract

import org.json.JSONObject
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
class ContractSelfTest {

    @Test
    fun `push data keeps the grovs keys over custom data and stringifies like the backend`() {
        val data = PushPayloads.data(custom = mapOf("grovs_campaign_id" to "spoof", "n" to 1, "list" to listOf(1, 2), "google.x" to "y"))
        assertEquals(PushPayloads.CAMPAIGN, data["grovs_campaign_id"])
        assertEquals("1", data["n"])
        assertEquals("[1,2]", data["list"])
        assertFalse(data.containsKey("google.x"))
    }

    @Test
    fun `a foreground remote message carries its data and notification`() {
        val message = PushPayloads.remoteMessage(image = "https://img.example/a.png")
        assertEquals(PushPayloads.CAMPAIGN, message.data["grovs_campaign_id"])
        assertEquals("Spring sale", message.notification!!.title)
        assertEquals("https://img.example/a.png", message.notification!!.imageUrl.toString())
        assertEquals("0:fg", message.messageId)
    }

    @Test
    fun `a data only remote message has no notification`() {
        assertNull(PushPayloads.remoteMessage(title = null, body = null).notification)
    }

    @Test
    fun `the authenticate fixture has every key the backend sends`() {
        val body = JSONObject(ContractBackend.authenticateBody())
        listOf("linksquared", "uri_scheme", "sdk_identifier", "sdk_attributes", "push_token", "has_smart_banners")
            .forEach { assertTrue(it, body.has(it)) }
    }

    @Test
    fun `the server reserved names include push_open and exclude custom and screen_view`() {
        assertTrue("push_open" in BackendContract.SERVER_RESERVED)
        assertFalse("custom" in BackendContract.SERVER_RESERVED)
        assertFalse("screen_view" in BackendContract.SERVER_RESERVED)
    }
}
