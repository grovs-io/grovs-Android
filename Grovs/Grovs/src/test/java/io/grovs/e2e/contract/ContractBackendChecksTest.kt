package io.grovs.e2e.contract

import io.grovs.service.ConsentBackend
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ContractBackendChecksTest {
    private val consent = ConsentBackend()
    private val contract = ContractBackend(consent).also { it.install() }
    private val client = OkHttpClient()

    @After fun close() = consent.close()

    private fun call(
        route: String,
        body: String = "{}",
        headers: Map<String, String> = mapOf("PROJECT-KEY" to "k", "PLATFORM" to "android", "IDENTIFIER" to "id", "LINKSQUARED" to "v1"),
    ): Response = client.newCall(
        Request.Builder().url(consent.baseUrl + "api/v1/sdk/" + route).post(body.toRequestBody()).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
    ).execute()

    private fun assertBroken(expected: String) {
        try {
            contract.assertKept()
        } catch (e: AssertionError) {
            assertTrue("message was: ${e.message}", e.message!!.contains(expected))
            return
        }
        fail("the contract check did not fire for '$expected'")
    }

    @Test
    fun `a reserved event name in a batch breaks the contract`() {
        call("events/batch", """{"events":[{"event_name":"push_open"}]}""").close()
        assertBroken("reserved")
    }

    @Test
    fun `a request without the device header breaks the contract`() {
        call("event/custom", """{"event_name":"x"}""", mapOf("PROJECT-KEY" to "k", "PLATFORM" to "android", "IDENTIFIER" to "id")).close()
        assertBroken("without LINKSQUARED")
    }

    @Test
    fun `a request without the project key breaks the contract`() {
        call("event/custom", """{"event_name":"x"}""", mapOf("PLATFORM" to "android", "IDENTIFIER" to "id", "LINKSQUARED" to "v1")).close()
        assertBroken("without PROJECT-KEY")
    }

    @Test
    fun `a route the backend does not have breaks the contract`() {
        call("not_a_route").close()
        assertBroken("route the backend does not have")
    }

    @Test
    fun `a route the fake does not answer breaks the contract`() {
        call("create_link").close()
        assertBroken("route the fake does not answer: create_link")
    }

    @Test
    fun `a JSON null event name counts as missing`() {
        val response = call("events/batch", """{"events":[{"event":null,"event_name":null}]}""")
        val body = response.body!!.string()
        assertTrue(body, body.contains("missing event or event_name"))
        assertBroken("missing event or event_name")
    }

    @Test
    fun `a blank user agent on authenticate is refused`() {
        val response = call("authenticate", """{"user_agent":" ","app_version":"1"}""")
        assertEquals(400, response.code)
        response.close()
        assertBroken("authenticate without user_agent or app_version")
    }

    @Test
    fun `a push open with a blank device header answers 403`() {
        val denied = call(
            "push_open", """{"campaign_id":"x"}""",
            mapOf("PROJECT-KEY" to "k", "PLATFORM" to "android", "IDENTIFIER" to "id", "LINKSQUARED" to " "),
        )
        assertEquals(403, denied.code)
        denied.close()
    }

    @Test
    fun `an inbox call breaks the contract`() {
        call("number_of_unread_notifications").close()
        assertBroken("inbox routes called")
    }

    @Test
    fun `a push open with a blank campaign breaks the contract`() {
        val response = call("push_open", """{"campaign_id":" "}""")
        assertEquals(400, response.code)
        response.close()
        assertBroken("push_open without a campaign_id")
    }

    @Test
    fun `a well formed session keeps the contract`() {
        call("events/batch", """{"events":[{"event":"app_open","event_id":"a"},{"event_name":"buy","event_id":"b"}]}""").close()
        val first = call("push_open", """{"campaign_id":"${PushPayloads.CAMPAIGN}"}""")
        assertEquals(BackendContract.PUSH_OPEN_RECORDED, first.body!!.string())
        val second = call("push_open", """{"campaign_id":"${PushPayloads.CAMPAIGN}"}""")
        assertEquals(BackendContract.PUSH_OPEN_ALREADY, second.body!!.string())
        val unknown = call("push_open", """{"campaign_id":"nope"}""")
        assertEquals(404, unknown.code)
        unknown.close()
        contract.assertKept()
    }

    @Test
    fun `a repeated event id is a duplicate only after the first event passed validation`() {
        val response = call(
            "events/batch",
            """{"events":[{"event_name":"push_open","event_id":"a"},{"event_name":"x","event_id":"a"},{"event_name":"y","event_id":" a "}]}""",
        )
        val body = response.body!!.string()
        assertTrue(body, body.contains(""""accepted":1"""))
        assertTrue(body, body.contains("duplicate event_id in batch"))
    }

    @Test
    fun `a numeric campaign id of an unknown campaign answers 404 and a missing device header answers 403 first`() {
        val ok = call("push_open", """{"campaign_id":12}""")
        assertEquals(404, ok.code)
        ok.close()
        val denied = call("push_open", """{"campaign_id":""}""", mapOf("PROJECT-KEY" to "k", "PLATFORM" to "android", "IDENTIFIER" to "id"))
        assertEquals(403, denied.code)
        denied.close()
    }
}
