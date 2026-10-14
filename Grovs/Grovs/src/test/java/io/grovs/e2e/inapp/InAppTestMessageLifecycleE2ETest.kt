package io.grovs.e2e.inapp

import android.content.Intent
import android.widget.TextView
import com.google.gson.JsonObject
import io.grovs.Grovs
import io.grovs.R
import io.grovs.e2e.contract.InAppBackend
import io.grovs.e2e.contract.InAppCampaigns
import io.grovs.e2e.contract.InAppCampaigns.appOpen
import io.grovs.e2e.contract.InAppCampaigns.button
import io.grovs.e2e.contract.InAppCampaigns.event
import io.grovs.handlers.GrovsManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
internal class InAppTestMessageLifecycleE2ETest : InAppTestBase() {

    private val syncGate = CountDownLatch(1)

    @After
    fun openSyncGate() = syncGate.countDown()

    private fun testMessage(id: String) = InAppCampaigns.campaign().id(id).title("Test $id")

    /** What the dashboard's "Send test" does: the message waits for the next full sync, and the user stamp moves. */
    private fun sendTest(message: JsonObject) {
        inApp.testMessages += message
        inApp.touch()
    }

    /** The title of the native message on screen, or null when none is. Test messages call no listener, so this is how a show is seen. */
    private fun titleOnScreen(): String? =
        presenter().shownWindow?.dialog?.window?.decorView?.findViewById<TextView>(R.id.grovs_in_app_title)?.text?.toString()

    /** An event the host app tracks, flushed now; its batch response carries the in-app stamps. */
    private fun trackAndFlush(name: String) {
        Grovs.track(name)
        reportedWhen(name)
    }

    /** The first sync is held on the server until [syncGate] opens, so the SDK decides app_open while it is in flight. */
    private fun holdFirstSync() {
        val first = AtomicBoolean(true)
        backend.answer("in_app/sync") { seen ->
            if (first.getAndSet(false)) syncGate.await(20, TimeUnit.SECONDS)
            inApp.syncAnswer(InAppBackend.parse(seen.body.ifBlank { "{}" }))
        }
    }

    // I52-I53: a test message "shows the message on every device of that person at its next sync within
    // 10 minutes, whatever its triggers, conditions and limits. Nothing is counted." Only the reopen path is checked.

    @Test
    fun `I52 a test message shows at the next sync whatever its triggers, conditions and caps`() {
        start()
        sendTest(testMessage("t1").triggers(event("never")).caps(lifetime = 1).condition(
            InAppCampaigns.raw("""{"type":"platform","op":"eq","value":"ios"}"""),
        ).build())
        reopen()
        pumpUntil("the test message on screen") { titleOnScreen() == "Test t1" }
    }

    @Test
    fun `I53 nothing is reported for a test message`() {
        start()
        sendTest(testMessage("t1").buttons(button("ok", "OK")).build())
        reopen()
        pumpUntil("t1 on screen") { titleOnScreen() == "Test t1" }
        tapButton("ok")
        pumpUntil("t1 closed by its button") { presenter().shownWindow == null }

        sendTest(testMessage("t2").build())
        reopen()
        pumpUntil("t2 on screen") { titleOnScreen() == "Test t2" }
        tapClose()
        pumpUntil("t2 closed") { presenter().shownWindow == null }

        trackAndFlush("control_event")
        assertEquals("control", 1, sent("control_event").size)
        val inAppEvents = contract.batchEvents().map { it.optString("event_name") }.filter { it.startsWith("in_app_") }
        assertTrue(inAppEvents.toString(), inAppEvents.isEmpty())
    }

    // Sending a test message "touches user_changed_at"; a stamp in the batch response that differs means
    // "sync now". That is the only way a test reaches a device whose app is in the background.
    @Test
    fun `I52 a test message received in the background shows on return`() {
        start()
        backgroundApp()
        sendTest(testMessage("t1").build())
        trackAndFlush("background_event")
        // The SDK spreads the sync over a random 0 to 10 s after the stamps changed.
        idleUntil("the background sync to take the test", 11_000) { inApp.testMessages.isEmpty() }
        awaitSyncHandled("the background sync", 0)
        assertNull("nothing shows while in the background", presenter().shownWindow)

        val syncs = inApp.syncBodies.size
        foreground(Intent())
        resumeHost()
        awaitSyncHandled("the foreground sync", syncs)
        pumpUntil("the test received in the background shows on return") { titleOnScreen() == "Test t1" }
    }

    // "shown when the screen is free"
    @Test
    fun `I52 a test waits behind the current message`() {
        showOne(InAppCampaigns.campaign().build())
        sendTest(testMessage("t1").build())
        val syncs = inApp.syncBodies.size
        trackAndFlush("x")
        idleUntil("a sync from the batch stamps", 11_000) { inApp.syncBodies.size > syncs }
        awaitSyncHandled("the sync that brings the test", syncs)
        assertEquals("c1 stays on screen", "Hello", titleOnScreen())

        closeShown()
        pumpUntil("the test after c1 closed") { titleOnScreen() == "Test t1" }
        assertEquals(listOf("c1"), recorder.shown)
    }

    // Disabling the SDK closes the message. The backend counts every dismissal
    // except orientation, so a close by disabling must not be reported.
    @Test
    fun `I54 disabling closes the message and reports nothing`() {
        showOne(InAppCampaigns.campaign().build())
        assertEquals("the view was reported", 1, reportedWhen("in_app_viewed").size)

        Grovs.setSDK(false)
        pumpUntil("closed by disabling") { presenter().shownWindow == null }
        assertTrue("no dismissal callback", recorder.dismissed.isEmpty())

        val syncs = inApp.syncBodies.size
        Grovs.setSDK(true)
        pumpUntil("authenticated again") { manager().authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED }
        awaitSyncHandled("in-app started again", syncs)
        Grovs.track("cart_viewed")
        pumpUntil("control: shown again") { recorder.shown.size == 2 }
        closeShown()
        val dismissals = reportedWhen("in_app_dismissed")
        assertEquals("only the control's close is reported", listOf("close_button"),
            dismissals.map { it.getJSONObject("properties").getString("reason") })
    }

    // "Background drops the delay and a delayed waiter"; cases README "Delay ends with the session"
    @Test
    fun `I55 going to the background drops a delayed message`() {
        start(InAppCampaigns.campaign().delay(5).build())
        trackAndFlush("cart_viewed")
        idleFor(1_000)
        reopen()
        idleFor(6_000)
        assertTrue("dropped by the background", recorder.shown.isEmpty())

        Grovs.track("cart_viewed")
        idleUntil("control: a new trigger shows after its delay", 6_000) { recorder.shown == listOf("c1") }
    }

    // cases README "Delay": "The display waits that many seconds and then decides again ... The view is recorded when the
    // message is shown, not at the trigger"
    @Test
    fun `I58 a delayed message waits out its delay and is counted when shown`() {
        start(InAppCampaigns.campaign().delay(5).build())
        // The event reaches the controller once it is queued, so the 5 s count from its delivery.
        trackAndFlush("cart_viewed")
        idleFor(4_000)
        assertTrue("not before 5 s", recorder.shown.isEmpty())
        assertTrue("no view before the show", reported("in_app_viewed").isEmpty())
        idleUntil("shown after 5 s", 2_000) { recorder.shown == listOf("c1") }
        assertEquals(1, reportedWhen("in_app_viewed").size)
    }

    // "Hold: app_open (and a landing screen) waits for the running sync or 2 s"

    @Test
    fun `I56 app_open waits for a sync that lands within 2 seconds`() {
        holdFirstSync()
        inApp.campaigns += InAppCampaigns.campaign().triggers(appOpen()).build()
        authenticated()
        resumeHost()
        pumpUntil("the first sync in flight") { backend.count("in_app/sync") >= 1 }
        idleFor(1_500)
        assertTrue("held while the sync runs", recorder.shown.isEmpty())
        syncGate.countDown()
        pumpUntil("app_open decided on the synced campaign") { recorder.shown == listOf("c1") }
    }

    @Test
    fun `I56 after 2 seconds app_open uses the stored campaigns`() {
        holdFirstSync()
        inApp.campaigns += InAppCampaigns.campaign().id("open").triggers(appOpen()).build()
        inApp.campaigns += InAppCampaigns.campaign().id("later").triggers(event("later")).build()
        authenticated()
        resumeHost()
        pumpUntil("the first sync in flight") { backend.count("in_app/sync") >= 1 }
        idleFor(2_500)
        syncGate.countDown()
        awaitStoredSync("the slow sync lands")
        settle(2_000)
        assertTrue("app_open was decided on the empty store", "open" !in recorder.shown)

        Grovs.track("later")
        pumpUntil("control: the synced campaigns work") { recorder.shown == listOf("later") }
    }

    // The SDK's own in-app UI is never tracked as a screen.
    @Test
    fun `I57 the SDK's own UI is never tracked as a screen view`() {
        inApp.campaigns += InAppCampaigns.campaign().build()
        Grovs.configure(
            application, "test-api-key", useTestEnvironment = true, baseURL = backend.baseUrl,
            autoTrackScreenViews = true, clipboardDomains = null, enabled = true,
        )
        foreground()
        resumeHost()
        pumpUntil("authentication") { manager().authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED }
        awaitStoredSync()
        Grovs.track("cart_viewed")
        pumpUntil("shown") { recorder.shown == listOf("c1") }
        closeShown()

        val screens = reportedWhen("screen_view").map { it.getJSONObject("properties").optString("screen_name") }
        assertTrue("control: the host activity was tracked", "TestActivity" in screens)
        assertTrue(screens.toString(), screens.none { it.contains("InApp", ignoreCase = true) || it.contains("grovs", ignoreCase = true) })
    }
}
