package io.grovs.e2e

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.Uri
import io.grovs.Grovs
import io.grovs.handlers.PendingLinkRetry
import io.grovs.handlers.RequestFailure
import io.grovs.service.GrovsService
import io.grovs.utils.InstantCompat
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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

    @Test
    fun `going to the background cancels the pending retry`() {
        tapDuringOutage()
        backendRecovers()

        background()
        advance(120_000)

        assertEquals("no retry from the background", 0, backend.count(payload, "recovered"))
        assertNotNull("the link is still pending", pending().slot)
    }

    @Test
    fun `a foreground outside the window replays the link at once`() {
        tapDuringOutage()
        backendRecovers()
        background()
        advance(120_000)

        // Past the window the failure opened.
        pending().backoff.elapsedMs = { Long.MAX_VALUE }
        foreground(Intent())

        pumpUntil("the replay right after foreground") { backend.count(payload, "recovered") >= 1 }
        pumpUntil("the link to be delivered") { Grovs.openedLinkDetails?.link == link }
    }

    @Test
    fun `a foreground inside the window waits it out`() {
        tapDuringOutage()
        backendRecovers()
        background()
        foreground(Intent())
        pump()

        assertEquals("nothing inside the window", 0, backend.count(payload, "recovered"))
        advanceUntil("the replay once the window ends") { backend.count(payload, "recovered") >= 1 }
        assertEquals("exactly one replay", 1, backend.count(payload, "recovered"))
    }

    @Test
    fun `consecutive failures widen the window`() {
        tapDuringOutage()
        // Still down: the first replay (10s later) spends a second budget, then waits 20s.
        advanceUntil("the second budget to be spent") { backend.count(payload) == budget * 2 }
        // The last attempt's response crosses a real socket, so parking it lags the request count
        // by a couple of dispatcher hops; wait for the park rather than asserting the instant after.
        pumpUntil("the second failure to be parked") { pending().slot != null }
        assertNotNull(pending().slot)

        advance(19_000)
        assertEquals("inside the doubled window", budget * 2, backend.count(payload))
        advance(2_000)
        pumpUntil("the third lookup to start after 20s") { backend.count(payload) >= budget * 2 + 1 }
    }

    private val other = "https://demo.sqd.link/other"

    @Test
    fun `a newer tapped link replaces the pending one`() {
        tapDuringOutage()
        backendRecovers()

        // The user taps a second link while the first is pending. It resolves normally.
        backend.respond(payload, """{"link":"$other","data":{}}""")
        foreground(tap(other))
        pumpUntil("the second link to be delivered") { Grovs.openedLinkDetails?.link == other }
        // Delivery happens before the replay's own outcome is recorded, one dispatch hop later:
        // pump once more so that the clear reaches the slot before the assertion below checks it.
        pump()

        assertNull("the first link is superseded", pending().slot)
        advance(120_000)
        val urls = backend.seen.filter { it.path == payload && it.phase == "recovered" }
            .map { JSONObject(it.body).getString("url") }
        assertEquals("only the second link was ever looked up again", listOf(other), urls)
    }

    @Test
    fun `a link tapped on the foreground that replays the pending one wins over the replay`() {
        tapDuringOutage()
        backend.respond(payload, """{"link":"$other","data":{}}""")
        backend.phase = "recovered"
        val delivered = mutableListOf<String?>()
        Grovs.setOnDeeplinkReceivedListener(null) { delivered += it.link }
        // The window has elapsed, so the foreground would replay the pending link at once.
        pending().backoff.elapsedMs = { Long.MAX_VALUE }

        foreground(tap(other))
        pumpUntil("the newer link to be delivered") { Grovs.openedLinkDetails?.link == other }
        advance(30_000)

        assertEquals("only the link the user tapped last is delivered", listOf(other), delivered)
        assertNull(pending().slot)
    }

    @Test
    fun `a rotation during the outage looks the same link up again and keeps it pending`() {
        tapDuringOutage()

        // Rotation: the launcher starts again with the same intent while the backend is still
        // down. The failed lookup left the intent unconsumed, so this is a fresh tapped-link
        // lookup that spends another budget and parks itself again.
        background()
        foreground(launcher().intent)
        pumpUntil("the rotation's lookup to start") { backend.count(payload) >= budget + 1 }
        advanceUntil("its budget to be spent") { backend.count(payload) == budget * 2 }
        pump()
        assertNotNull("the link is still pending", pending().slot)

        backendRecovers()
        advanceUntil("the timer to replay the pending link") { Grovs.openedLinkDetails?.link == link }
        assertEquals("one replay after recovery", 1, backend.count(payload, "recovered"))
    }

    @Test
    fun `disabling the SDK drops the pending link and re-enabling never replays it`() {
        tapDuringOutage()
        backendRecovers()

        Grovs.setSDK(false)
        pump()
        assertNull("withdrawn consent owns no pending link", pending().slot)

        Grovs.setSDK(true)
        pumpUntil("re-authentication") {
            manager().authenticationState == io.grovs.handlers.GrovsManager.AuthenticationState.AUTHENTICATED
        }
        advance(300_000)
        assertEquals("a withdrawn link is never looked up again", 0, backend.count(payload, "recovered"))
        assertNull(Grovs.openedLinkDetails)
    }

    @Test
    fun `a background longer than the session drops the pending link`() {
        tapDuringOutage()
        backendRecovers()
        pending().backoff.elapsedMs = { Long.MAX_VALUE }

        background()
        // Longer than GrovsContext.SESSION_TIMEOUT_MS: the next foreground starts a new session.
        // backgroundedAt has a private setter, so it is written through reflection here.
        val backgroundedAt = context.javaClass.getDeclaredField("backgroundedAt").apply { isAccessible = true }
        backgroundedAt.set(context, InstantCompat.now().minusMillis(60L * 60L * 1000L))
        foreground(Intent())
        pump()

        assertNull("the tap belonged to a session that is over", pending().slot)
        advance(300_000)
        assertEquals(0, backend.count(payload, "recovered"))
    }

    @Test
    fun `a lookup that failed offline replays as soon as the network returns`() {
        backend.respond(authenticate, authOk)
        configure()
        foreground()
        pumpUntil("login") {
            manager().authenticationState == io.grovs.handlers.GrovsManager.AuthenticationState.AUTHENTICATED
        }

        val connectivity = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val onlineInfo: NetworkInfo? = connectivity.activeNetworkInfo
        backend.fail(payload)
        shadowOf(connectivity).setActiveNetworkInfo(null)
        foreground(tap())
        pumpUntil("the first lookup attempt") { backend.count(payload) >= 1 }
        advanceUntil("the budget to be spent offline") { pending().slot != null }
        assertEquals(RequestFailure.OFFLINE, pending().backoff.lastFailure)

        backendRecovers()
        shadowOf(connectivity).setActiveNetworkInfo(onlineInfo)
        val network = connectivity.activeNetwork!!
        shadowOf(connectivity).networkCallbacks.forEach { it.onAvailable(network) }
        pump()

        // pumpUntil never moves virtual time, so the 10s timer cannot be what replays here.
        pumpUntil("the replay right after the network returned") { backend.count(payload, "recovered") >= 1 }
        pumpUntil("the link to be delivered") { Grovs.openedLinkDetails?.link == link }
    }

    @Test
    fun `a backend outage does not replay on a network change`() {
        tapDuringOutage()
        backendRecovers()

        val connectivity = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork!!
        shadowOf(connectivity).networkCallbacks.forEach { it.onAvailable(network) }
        pump()

        assertEquals("only the timer retries a backend outage", 0, backend.count(payload, "recovered"))
    }

    @Test
    fun `a new configuration drops the pending link and a fresh tap waits in the login slot`() {
        tapDuringOutage()
        backendRecovers()

        // A new configuration logs in again. Its login fails, so the SDK is logged out.
        backend.failWithStatus(authenticate, code = 500, body = """{"error":"down"}""")
        configure()
        pumpUntil("the new configuration's login attempt to fail") { backend.count(authenticate, "recovered") >= 1 }
        advance(60_000)
        assertNull("a new configuration owns no pending link", pending().slot)

        // Tapped while logged out: the intent parks in the login slot, never in the link slot.
        foreground(tap())
        advance(60_000)
        assertNull("nothing looks up a link while logged out", pending().slot)
        assertEquals("no lookup while logged out", 0, backend.count(payload, "recovered"))

        backend.respond(authenticate, authOk)
        manager().authenticationBackoffElapsedMs = { Long.MAX_VALUE }
        foreground(Intent())
        pumpUntil("login, then the parked link, then delivery") { Grovs.openedLinkDetails?.link == link }
        assertEquals("one lookup, replayed from the login slot", 1, backend.count(payload, "recovered"))
        assertNull(pending().slot)
    }
}
