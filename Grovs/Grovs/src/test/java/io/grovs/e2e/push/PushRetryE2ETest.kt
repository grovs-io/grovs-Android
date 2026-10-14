package io.grovs.e2e.push

import android.content.Intent
import io.grovs.e2e.contract.BackendContract
import io.grovs.e2e.contract.BackendContract.IN_REQUEST_ATTEMPTS
import io.grovs.e2e.contract.BackendContract.REPORT_ATTEMPTS
import io.grovs.e2e.contract.ContractTestBase
import io.grovs.e2e.contract.PushPayloads
import io.grovs.e2e.contract.PushPayloads.tapIntent
import io.grovs.utils.InstantCompat
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
internal class PushRetryE2ETest : ContractTestBase() {

    private val tap get() = tapIntent(PushPayloads.data(link = null))

    /** Taps, lets the first report spend its in-request tries, then recovers and foregrounds. */
    private fun firstReportFailsThenRecovers() {
        authenticated()
        foreground(tap)
        advanceUntil("the first report's tries") { pushOpens() >= IN_REQUEST_ATTEMPTS }
        settle(30_000)
        contract.install()
        backend.phase = "recovered"
        foreground(Intent())
        pumpUntil("the retried open is reported") { pushOpens("recovered") >= 1 }
        settle(10_000)
    }

    @Test
    fun `P21 a 5xx open is retried on the next foreground`() {
        backend.failWithStatus("push_open", code = 503, body = "down")
        firstReportFailsThenRecovers()
        assertEquals(1, pushOpens("recovered"))
    }

    @Test
    fun `P21 a retried open keeps the session of the tap`() {
        backend.failWithStatus("push_open", code = 503, body = "down")
        authenticated()
        foreground(tap)
        advanceUntil("the first report's tries") { pushOpens() >= IN_REQUEST_ATTEMPTS }
        settle(30_000)
        val tapSession = contract.bodies("push_open").first().getString("session_id")

        background()
        settle(2_000)
        // The session timeout is measured on the wall clock, which the test cannot move.
        val context = manager().grovsContext
        context.javaClass.getDeclaredField("backgroundedAt").apply { isAccessible = true }
            .set(context, InstantCompat.ofEpochMilli(System.currentTimeMillis() - 31 * 60_000L))
        contract.install()
        backend.phase = "recovered"
        foreground(Intent())
        pumpUntil("the retried open is reported") { pushOpens("recovered") >= 1 }

        assertNotEquals("a new session started", tapSession, context.sessionId)
        val retried = backend.seen.filter { it.path == "push_open" && it.phase == "recovered" }
        assertEquals(tapSession, JSONObject(retried.single().body).getString("session_id"))
    }

    @Test
    fun `P21 a 429 open is retried on the next foreground`() {
        backend.failWithStatus("push_open", code = 429, body = """{"error":"slow down"}""")
        firstReportFailsThenRecovers()
        assertEquals(1, pushOpens("recovered"))
    }

    @Test
    fun `P21 a dropped connection is retried on the next foreground`() {
        backend.fail("push_open")
        firstReportFailsThenRecovers()
        assertEquals(1, pushOpens("recovered"))
    }

    @Test
    fun `P21 an open that keeps failing is dropped after three reports`() {
        backend.failWithStatus("push_open", code = 503, body = "down")
        authenticated()
        foreground(tap)
        repeat(REPORT_ATTEMPTS + 2) {
            settle(60_000)
            foreground(Intent())
        }
        advanceUntil("12 opens") { pushOpens() >= REPORT_ATTEMPTS * IN_REQUEST_ATTEMPTS }
        settle(60_000)

        assertEquals(REPORT_ATTEMPTS * IN_REQUEST_ATTEMPTS, pushOpens())
    }

    private fun permanentFailure(code: Int, body: String) {
        backend.failWithStatus("push_open", code = code, body = body)
        authenticated()
        foreground(tap)
        pumpUntil("the open") { pushOpens() >= 1 }
        settle(30_000)
        repeat(2) { foreground(Intent()); settle(30_000) }
        assertEquals("$code is final: one request, no retry", 1, pushOpens())
    }

    @Test
    fun `P22 a 400 open is dropped`() = permanentFailure(400, """{"error":"campaign_id is required"}""")

    @Test
    fun `P22 a 403 open is dropped`() = permanentFailure(403, """{"error":"Invalid linksquared id"}""")

    @Test
    fun `P22 a 404 open for an unknown campaign is dropped`() {
        contract.knownPushCampaigns = emptySet()
        authenticated()
        foreground(tap)
        pumpUntil("the open") { pushOpens() >= 1 }
        settle(30_000)
        repeat(2) { foreground(Intent()); settle(30_000) }
        assertEquals(1, pushOpens())
    }

    @Test
    fun `P23 already recorded is a success and is not retried`() {
        backend.respond("push_open", BackendContract.PUSH_OPEN_ALREADY)
        authenticated()
        foreground(tap)
        pumpUntil("the open") { pushOpens() >= 1 }
        settle(30_000)
        repeat(2) { foreground(Intent()); settle(30_000) }
        assertEquals(1, pushOpens())
    }

    @Test
    fun `P23 a 200 with an unexpected body is still a success`() {
        backend.respond("push_open", "{}")
        authenticated()
        foreground(tap)
        pumpUntil("the open") { pushOpens() >= 1 }
        settle(30_000)
        repeat(2) { foreground(Intent()); settle(30_000) }
        assertEquals(1, pushOpens())
    }
}
