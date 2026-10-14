package io.grovs.e2e.inapp

import android.content.Intent
import android.util.Log
import io.grovs.Grovs
import io.grovs.GrovsInAppListener
import io.grovs.GrovsInAppListenerAdapter
import io.grovs.e2e.contract.InAppBackend.SyncMode
import io.grovs.e2e.contract.InAppCampaigns
import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
internal class InAppControlsListenerE2ETest : InAppTestBase() {

    private val c1 get() = InAppCampaigns.campaign().build()

    private fun stateFile() = File(storeFolder(), "in_app_state.json")
    private fun campaignsFile() = File(storeFolder(), "in_app_campaigns.json")

    // I42: "Unreadable file: start empty and sync."

    @Test
    fun `I42 stored campaigns survive a restart and show while the sync fails`() {
        startSettled(c1)
        inApp.mode = SyncMode.FAIL
        restart()
        Grovs.track("cart_viewed")
        pumpUntil("the stored campaign shows offline") { recorder.shown == listOf("c1") }
    }

    @Test
    fun `I42 a damaged state file starts empty without crashing`() {
        startSettled(InAppCampaigns.campaign().caps(lifetime = 1).build())
        Grovs.track("cart_viewed")
        pumpUntil("first view") { recorder.shown == listOf("c1") }
        closeShown()
        pumpUntil("the view is stored") { stateFile().takeIf { it.exists() }?.readText()?.contains("\"c1\"") == true }

        restart()
        Grovs.track("cart_viewed")
        settle(3_000)
        assertEquals("the stored view keeps the lifetime cap", listOf("c1"), recorder.shown)

        restart { stateFile().writeText("{\"views\":[{\"id\": broken") }
        Grovs.track("cart_viewed")
        pumpUntil("shown again: the damaged state started empty") { recorder.shown == listOf("c1", "c1") }
    }

    @Test
    fun `I42 a damaged campaigns file starts empty and the next sync fills it`() {
        startSettled(c1)
        inApp.mode = SyncMode.FAIL
        restart { campaignsFile().writeText("not json at all") }
        Grovs.track("cart_viewed")
        settle(3_000)
        assertTrue("nothing to show from a damaged file", recorder.shown.isEmpty())

        inApp.mode = SyncMode.UNCHANGED_WHEN_SAME
        reopen()
        Grovs.track("cart_viewed")
        pumpUntil("shown after the next sync") { recorder.shown == listOf("c1") }
    }

    // I43-I46: README "Control when messages may appear"

    @Test
    fun `I43 paused messages are held and shown on resume`() {
        start(c1)
        Grovs.pauseInAppMessages()
        Grovs.track("cart_viewed")
        settle(3_000)
        assertTrue(recorder.shown.isEmpty())
        Grovs.resumeInAppMessages()
        pumpUntil("shown on resume") { recorder.shown == listOf("c1") }
    }

    @Test
    fun `I44 discard drops what waits`() {
        start(c1)
        Grovs.pauseInAppMessages()
        Grovs.track("cart_viewed")
        settle(1_000)
        Grovs.discardInAppMessages()
        Grovs.resumeInAppMessages()
        settle(3_000)
        assertTrue(recorder.shown.isEmpty())
        Grovs.track("cart_viewed")
        pumpUntil("control: a new trigger shows") { recorder.shown == listOf("c1") }
    }

    @Test
    fun `I44 discard stays paused`() {
        start(c1)
        Grovs.pauseInAppMessages()
        Grovs.discardInAppMessages()
        Grovs.track("cart_viewed")
        settle(3_000)
        assertTrue("still paused after discard", recorder.shown.isEmpty())
        Grovs.resumeInAppMessages()
        pumpUntil("the trigger made after discard shows on resume") { recorder.shown == listOf("c1") }
    }

    @Test
    fun `I44 discard drops a delayed message`() {
        start(InAppCampaigns.campaign().delay(5).build())
        Grovs.track("cart_viewed")
        idleFor(1_000)
        Grovs.discardInAppMessages()
        Grovs.resumeInAppMessages()
        idleFor(8_000)
        assertTrue(recorder.shown.isEmpty())
        Grovs.track("cart_viewed")
        idleUntil("control: a new trigger shows after its delay", 8_000) { recorder.shown == listOf("c1") }
    }

    @Test
    fun `I44 discard with dismissCurrent closes the current message with app`() {
        start(c1)
        Grovs.track("cart_viewed")
        pumpUntil("shown") { recorder.shown.isNotEmpty() }
        Grovs.discardInAppMessages(dismissCurrent = true)
        pumpUntil("closed") { recorder.dismissed.isNotEmpty() }
        assertEquals("c1" to "app", recorder.dismissed.single())
        assertEquals("app", reportedWhen("in_app_dismissed").single().getJSONObject("properties").getString("reason"))
    }

    @Test
    fun `I45 an excluded screen holds the message until the user leaves it`() {
        Grovs.setInAppExcludedScreens(listOf("CHECKOUT"))
        start(c1)
        Grovs.trackScreenView("checkout")
        Grovs.track("cart_viewed")
        settle(3_000)
        assertTrue(recorder.shown.isEmpty())
        Grovs.trackScreenView("home")
        pumpUntil("shown after leaving") { recorder.shown == listOf("c1") }
    }

    @Test
    fun `I46 settings set before configure survive and debug logging writes INFO under GrovsInApp`() {
        Grovs.setInAppDebugLogging(true)
        Grovs.pauseInAppMessages()
        start(c1)
        DebugLogger.instance.logLevel = LogLevel.ERROR
        Grovs.track("cart_viewed")
        settle(2_000)
        assertTrue("the pause set before configure holds", recorder.shown.isEmpty())
        assertTrue(ShadowLog.getLogsForTag("GrovsInApp").any { it.type == Log.INFO })
        Grovs.resumeInAppMessages()
        pumpUntil("shown") { recorder.shown == listOf("c1") }
    }

    // I47-I51: README "Optional hooks", CHANGELOG

    @Test
    fun `I47 every callback runs on the main thread`() {
        start(c1)
        Grovs.track("cart_viewed")
        pumpUntil("shown") { recorder.shown.isNotEmpty() }
        closeShown()
        assertEquals(listOf("should", "shown", "dismissed"), recorder.calls.map { it.kind })
        assertTrue(recorder.calls.toString(), recorder.calls.all { it.onMain })
    }

    @Test
    fun `I47 the listener is held strongly`() {
        val shown = CopyOnWriteArrayList<String>()
        Grovs.setInAppListener(object : GrovsInAppListener {
            override fun onInAppMessageShown(campaignId: String) { shown += campaignId }
        })
        start(c1)
        repeat(3) { System.gc(); System.runFinalization() }
        Grovs.track("cart_viewed")
        pumpUntil("the only reference is the SDK's") { shown == listOf("c1") }
    }

    @Test
    fun `I47 setInAppListener null releases the listener`() {
        start(c1)
        Grovs.setInAppListener(null)
        Grovs.track("cart_viewed")
        reportedWhen("in_app_viewed")
        assertTrue(recorder.calls.isEmpty())
    }

    @Test
    fun `I48 a veto shows and reports nothing, and nothing else shows for that event`() {
        recorder.veto = { it != "c1" }
        start(
            InAppCampaigns.campaign().id("c1").priority(5).build(),
            InAppCampaigns.campaign().id("c2").priority(1).build(),
        )
        Grovs.track("cart_viewed")
        settle(3_000)
        assertEquals("the winner was asked", listOf("c1"), recorder.of("should").map { it.campaignId })
        assertTrue("c1 vetoed and c2 does not take its place", recorder.shown.isEmpty())
        recorder.veto = { true }
        Grovs.track("cart_viewed")
        pumpUntil("control") { recorder.shown == listOf("c1") }
        assertEquals("only the control was viewed", 1, reportedWhen("in_app_viewed").size)
    }

    @Test
    fun `I49 shown is reported with one view, then dismissed carries the reason`() {
        start(c1)
        Grovs.track("cart_viewed")
        pumpUntil("shown") { recorder.shown.isNotEmpty() }
        assertEquals(1, reportedWhen("in_app_viewed").size)
        closeShown()
        assertEquals("c1" to "close_button", recorder.dismissed.single())
        assertEquals(listOf("shown", "dismissed"), recorder.calls.map { it.kind }.filter { it != "should" })
    }

    @Test
    fun `I50 a message with no screen to draw on fails, counts nothing and shows at its next trigger`() {
        start(c1)
        // The app closes its only activity; the SDK still sees it until it stops.
        launcher().finish()
        Grovs.track("cart_viewed")
        pumpUntil("the failure callback") { recorder.failed.isNotEmpty() }
        assertEquals("c1", recorder.failed.single().first)
        assertTrue(recorder.shown.isEmpty())

        foreground(Intent())
        resumeHost()
        Grovs.track("cart_viewed")
        pumpUntil("shown at the next trigger") { recorder.shown == listOf("c1") }
        assertEquals("only the drawn message was viewed", 1, reportedWhen("in_app_viewed").size)
    }

    @Test
    fun `I51 the Java adapter works with no overrides`() {
        Grovs.setInAppListener(object : GrovsInAppListenerAdapter() {})
        start(c1)
        Grovs.track("cart_viewed")
        reportedWhen("in_app_viewed")
    }
}
