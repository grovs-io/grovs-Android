package io.grovs.e2e

import android.content.Intent
import android.net.Uri
import io.grovs.Grovs
import io.grovs.handlers.PendingLinkRetry
import io.grovs.service.GrovsService
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Virtual time, jitter pinned to zero: a lookup's budget ends 14s after its first attempt (waits
 * of 2s, 4s, 8s) and the first automatic retry waits 10s after that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PendingLinkRecoveryE2ETest : DrivenBackendTestBase() {

    private val link = "https://demo.sqd.link/campaign"
    private val budget = GrovsService.MAX_ATTEMPTS.toInt()

    private fun tap(url: String = link) = Intent(Intent.ACTION_VIEW, Uri.parse(url))

    private fun pending(): PendingLinkRetry = E2ETestUtils.getPendingLinkRetry()!!

    /** Opens the app from [url] with the lookup failing, and spends the budget. */
    private fun tapDuringOutage(url: String = link, code: Int = 503) {
        backend.respond(authenticate, authOk)
        backend.failWithStatus(payload, code = code, body = "no available server")
        configure()
        foreground(tap(url))
        pumpUntil("the first lookup attempt") { backend.count(payload) >= 1 }
        advanceUntil("the lookup budget to be spent") { pending().slot != null }
        assertEquals("the budget is spent", budget, backend.count(payload))
        assertNull("nothing was delivered", Grovs.openedLinkDetails)
    }

    private fun backendRecovers() {
        backend.respond(payload, """{"link":"$link","data":{"screen":"campaign"}}""")
        backend.phase = "recovered"
    }

    @Test
    fun `a link tapped during an outage arrives once the backend is back while the user stays in the app`() {
        tapDuringOutage()
        backendRecovers()

        advanceUntil("the pending link to be delivered by the timer") { Grovs.openedLinkDetails?.link == link }
        // Delivery happens before the replay's own outcome is recorded, one dispatch hop later:
        // pump once more so that outcome lands before the slot-is-empty assertion below checks it.
        pump()

        assertEquals("one replay", 1, backend.count(payload, "recovered"))
        assertEquals(link, JSONObject(backend.seen.last { it.path == payload }.body).getString("url"))
        assertNull("the slot is empty after a success", pending().slot)
    }

    @Test
    fun `the first automatic retry waits 10s`() {
        tapDuringOutage()
        backendRecovers()

        advance(9_000)
        assertEquals("nothing inside the window", 0, backend.count(payload, "recovered"))
        advance(2_000)
        // The request crosses a real socket, so wait for it rather than asserting the instant after.
        pumpUntil("the retry to go out") { backend.count(payload, "recovered") >= 1 }
        assertEquals("exactly one retry", 1, backend.count(payload, "recovered"))
    }

    @Test
    fun `a 4xx on the lookup is final`() {
        backend.respond(authenticate, authOk)
        backend.failWithStatus(payload, code = 404, body = """{"error":"unknown link"}""")
        configure()
        foreground(tap())
        pumpUntil("the lookup to be refused") { backend.count(payload) >= 1 }
        advance(120_000)

        assertEquals("one request, no retry", 1, backend.count(payload))
        assertNull(pending().slot)
    }
}
