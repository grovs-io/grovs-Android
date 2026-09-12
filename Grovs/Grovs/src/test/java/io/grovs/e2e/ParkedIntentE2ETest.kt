package io.grovs.e2e

import android.content.Intent
import android.net.Uri
import io.grovs.Grovs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ParkedIntentE2ETest : DrivenBackendTestBase() {

    private val link = "https://demo.sqd.link/parked"

    @Test
    fun `a link tapped before authentication resolves once authentication succeeds`() {
        backend.failWithStatus(authenticate, code = 500, body = """{"error":"down"}""")
        backend.respond(payload, """{"link":"campaign-link","data":{"k":"v"}}""")
        configure()
        pumpUntil("the launch attempt to fail") { backend.count(authenticate) >= 1 }
        advance(60_000)

        // The user taps the campaign link while the SDK is unauthenticated.
        foreground(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        assertEquals("nothing is resolved while unauthenticated", 0, backend.count(payload))

        // The backend recovers. The blank intent keeps this foreground from carrying the link
        // itself, so only the replay can resolve it.
        backend.respond(authenticate, authOk)
        backend.phase = "recovered"
        manager().authenticationBackoffElapsedMs = { Long.MAX_VALUE }
        foreground(Intent())

        pumpUntil("the parked link to resolve") { backend.count(payload) >= 1 }
        pump()

        assertEquals("the parked link was replayed exactly once", 1, backend.count(payload))
        assertEquals(link, JSONObject(backend.seen.last { it.path == payload }.body).getString("url"))
    }

    @Test
    fun `a parked link is dropped when consent is withdrawn before authentication`() {
        backend.failWithStatus(authenticate, code = 500, body = """{"error":"down"}""")
        backend.respond(payload, """{"link":"campaign-link","data":{"k":"v"}}""")
        configure()
        pumpUntil("the launch attempt to fail") { backend.count(authenticate) >= 1 }
        advance(60_000)

        foreground(Intent(Intent.ACTION_VIEW, Uri.parse(link)))

        Grovs.setSDK(false)
        backend.phase = "disabled"
        pump()

        backend.respond(authenticate, authOk)
        Grovs.setSDK(true)
        backend.phase = "re-enabled"
        advance(300_000)
        foreground(Intent())

        assertEquals("a withdrawn link is never resolved", 0, backend.count(payload, "re-enabled"))
    }
}
