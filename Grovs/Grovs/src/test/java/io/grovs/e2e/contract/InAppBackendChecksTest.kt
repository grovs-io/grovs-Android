package io.grovs.e2e.contract

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.grovs.service.ConsentBackend
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InAppBackendChecksTest {

    private val consent = ConsentBackend()
    private val contract = ContractBackend(consent).also { it.install() }
    private val inApp = InAppBackend().also { contract.inApp = it }
    private val http = OkHttpClient()

    @Before fun campaign() {
        inApp.campaigns += InAppCampaigns.campaign().buttons(InAppCampaigns.button("buy", "Buy")).question(null).build()
        inApp.campaigns += InAppCampaigns.campaign().id("q1").question(InAppCampaigns.stars(reviewFrom = null, comment = true)).build()
    }

    @After fun close() = consent.close()

    private fun post(path: String, body: String): JsonObject {
        val request = Request.Builder().url(consent.baseUrl + "api/v1/sdk/$path")
            .header("PROJECT-KEY", "k").header("PLATFORM", "android").header("IDENTIFIER", "io.test")
            .header("LINKSQUARED", "visitor-1").post(body.toRequestBody()).build()
        http.newCall(request).execute().use { return JsonParser.parseString(it.body!!.string()).asJsonObject }
    }

    private fun batch(vararg events: String) = post("events/batch", """{"events":[${events.joinToString(",")}]}""")

    private fun event(name: String, properties: String, id: String = name) =
        """{"event_id":"$id","event_name":"$name","created_at":"2026-10-14T10:00:00Z","properties":$properties}"""

    private fun assertBroken(expected: String) {
        try { contract.assertKept(); fail("expected a contract violation containing '$expected'") }
        catch (e: AssertionError) { assertTrue(e.message, e.message!!.contains(expected)) }
    }

    @Test fun `full sync answers like in_app_sync`() {
        val answer = post("in_app/sync", """{"campaigns_changed_at":null,"user_changed_at":null,"format":1,"sdk_version":"3.1.0"}""")
        listOf("campaigns_changed_at", "user_changed_at", "server_time", "store_url", "first_seen_at", "global_caps", "campaigns", "test_messages")
            .forEach { assertTrue(it, answer.has(it)) }
        assertEquals(2, answer.getAsJsonArray("campaigns").size())
    }

    @Test fun `held stamps that match answer unchanged`() {
        val first = post("in_app/sync", """{"format":1,"sdk_version":"x"}""")
        val again = post("in_app/sync", """{"campaigns_changed_at":"${first.get("campaigns_changed_at").asString}","user_changed_at":"${first.get("user_changed_at").asString}","format":1,"sdk_version":"x"}""")
        assertTrue(again.get("unchanged").asBoolean)
        assertFalse(again.has("campaigns"))
    }

    @Test fun `a test message is handed over once`() {
        inApp.testMessages += InAppCampaigns.campaign().id("t1").build()
        assertEquals(1, post("in_app/sync", """{"format":1}""").getAsJsonArray("test_messages").size())
        assertEquals(0, post("in_app/sync", """{"format":1}""").getAsJsonArray("test_messages").size())
    }

    @Test fun `a valid view click dismiss answer and comment keep the contract`() {
        batch(
            event("in_app_viewed", """{"campaign_id":"c1","revision":1,"template":"interstitial"}""", "v1"),
            event("in_app_clicked", """{"campaign_id":"c1","revision":1,"action":"close","button_id":"buy"}""", "e1"),
            event("in_app_dismissed", """{"campaign_id":"c1","revision":1,"reason":"close_button"}""", "e2"),
            event("in_app_answered", """{"campaign_id":"q1","revision":1,"question_id":"q","type":"stars","value":4}""", "e3"),
            event("in_app_commented", """{"campaign_id":"q1","revision":1,"question_id":"q","type":"stars","value":4,"comment":"nice"}""", "e4"),
        )
        contract.assertKept()
    }

    @Test fun `a float star answer is refused`() {
        batch(event("in_app_answered", """{"campaign_id":"q1","revision":1,"question_id":"q","type":"stars","value":4.0}"""))
        assertBroken("in_app_answered")
    }

    @Test fun `an unknown campaign is refused`() {
        batch(event("in_app_viewed", """{"campaign_id":"nope","revision":1,"template":"interstitial"}"""))
        assertBroken("in_app_viewed")
    }

    @Test fun `an unknown button is refused`() {
        batch(event("in_app_clicked", """{"campaign_id":"c1","revision":1,"action":"close","button_id":"ghost"}"""))
        assertBroken("in_app_clicked")
    }

    @Test fun `a second answer by the same person is refused`() {
        batch(event("in_app_answered", """{"campaign_id":"q1","revision":1,"question_id":"q","type":"stars","value":4}""", "a1"))
        batch(event("in_app_answered", """{"campaign_id":"q1","revision":1,"question_id":"q","type":"stars","value":5}""", "a2"))
        assertBroken("in_app_answered")
    }

    @Test fun `a comment without an answer is refused`() {
        batch(event("in_app_commented", """{"campaign_id":"q1","revision":1,"question_id":"q","type":"stars","value":4,"comment":"x"}"""))
        assertBroken("in_app_commented")
    }

    @Test fun `a comment over 1000 characters is refused`() {
        batch(
            event("in_app_answered", """{"campaign_id":"q1","revision":1,"question_id":"q","type":"stars","value":4}""", "a1"),
            event("in_app_commented", """{"campaign_id":"q1","revision":1,"question_id":"q","type":"stars","value":4,"comment":"${"x".repeat(1001)}"}""", "c1"),
        )
        assertBroken("in_app_commented")
    }

    @Test fun `a server only message event is refused`() {
        batch(event("in_app_held", """{"campaign_id":"c1","revision":1}"""))
        assertBroken("in_app_held")
    }

    @Test fun `in-app off refuses every message event`() {
        contract.inApp = null
        batch(event("in_app_viewed", """{"campaign_id":"c1","revision":1,"template":"interstitial"}"""))
        assertBroken("in_app_viewed")
    }
}
