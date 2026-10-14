package io.grovs.e2e.inapp

import com.google.gson.JsonObject
import io.grovs.Grovs
import io.grovs.e2e.contract.InAppBackend
import io.grovs.e2e.contract.InAppBackend.SyncMode
import io.grovs.e2e.contract.InAppCampaigns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
internal class InAppSyncE2ETest : InAppTestBase() {

    private val c1 get() = InAppCampaigns.campaign().build()

    private fun syncRequests() = backend.seen.count { it.path == "in_app/sync" }

    private fun JsonObject.isNullOrAbsent(key: String) = get(key) == null || get(key).isJsonNull

    @Test
    fun `I1 I3 the first sync follows authentication with null stamps, format 1 and the sdk version`() {
        start(c1)
        val first = inApp.syncBodies.first()
        assertTrue(first.isNullOrAbsent("campaigns_changed_at"))
        assertTrue(first.isNullOrAbsent("user_changed_at"))
        assertEquals(1, first.get("format").asInt)
        assertTrue(first.get("sdk_version").asString.isNotBlank())
        val paths = backend.seen.map { it.path }
        assertTrue("authenticated before syncing", paths.indexOf("authenticate") in 0 until paths.indexOf("in_app/sync"))
    }

    @Test
    fun `I1 no sync while the SDK is disabled`() {
        inApp.campaigns += c1
        configure(enabled = false)
        foreground()
        settle(10_000)
        assertEquals(0, syncRequests())

        Grovs.setSDK(true)
        pumpUntil("a sync once enabled") { syncRequests() > 0 }
    }

    @Test
    fun `I2 a foreground syncs again with the held stamps`() {
        startSettled(c1)
        val before = syncRequests()
        reopen()
        assertEquals(before + 1, syncRequests())
        assertEquals(inApp.campaignsStamp, inApp.syncBodies.last().get("campaigns_changed_at").asString)
        assertEquals(inApp.userStamp, inApp.syncBodies.last().get("user_changed_at").asString)
    }

    @Test
    fun `I2 new stamps in a batch answer sync again within 10 seconds`() {
        startSettled(c1)
        val before = syncRequests()
        inApp.touch()
        Grovs.track("anything")
        flushEvents()
        idleUntil("a sync after new stamps", 11_000) { syncRequests() > before }
        assertEquals(before + 1, syncRequests())
    }

    @Test
    fun `I2 while active it syncs every 15 minutes`() {
        startSettled(c1)
        val before = syncRequests()
        idleFor(14 * 60_000)
        assertEquals("not before 15 minutes", before, syncRequests())
        idleUntil("the periodic sync", 120_000) { syncRequests() > before }
        assertEquals(before + 1, syncRequests())
    }

    @Test
    fun `I4 unchanged keeps the campaigns`() {
        startSettled(c1)
        val before = syncRequests()
        reopen()
        assertEquals(before + 1, syncRequests())
        Grovs.track("cart_viewed")
        pumpUntil("the kept campaign shows") { recorder.shown == listOf("c1") }
    }

    @Test
    fun `I4 off empties the device`() {
        showOne(c1)
        closeShown()
        inApp.mode = SyncMode.OFF
        reopen()
        Grovs.track("cart_viewed")
        settle(3_000)
        assertEquals("no second message after off", listOf("c1"), recorder.shown)

        inApp.mode = SyncMode.FULL
        inApp.touch()
        reopen()
        Grovs.track("cart_viewed")
        pumpUntil("the campaign returns once the server has it again") { recorder.shown == listOf("c1", "c1") }
    }

    @Test
    fun `I4 an answer without stamps is applied as a full answer`() {
        inApp.mode = SyncMode.NO_STAMPS
        startSettled(c1)
        Grovs.track("cart_viewed")
        pumpUntil("shown from a stampless sync") { recorder.shown == listOf("c1") }
    }

    @Test
    fun `I4 a failed sync keeps the stored campaigns`() {
        startSettled(c1)
        inApp.mode = SyncMode.FAIL
        val before = syncRequests()
        reopen()
        assertEquals(before + 1, syncRequests())
        Grovs.track("cart_viewed")
        pumpUntil("the stored campaign still shows") { recorder.shown == listOf("c1") }
    }

    @Test
    fun `I4 a server without in-app (404) is a quiet failure`() {
        inApp.mode = SyncMode.NOT_FOUND
        inApp.campaigns += c1
        authenticated()
        pumpUntil("the 404 sync") { syncRequests() > 0 }
        Grovs.track("cart_viewed")
        settle(5_000)
        assertTrue(recorder.shown.isEmpty())
        assertTrue(recorder.of("failed").isEmpty())

        inApp.mode = SyncMode.UNCHANGED_WHEN_SAME
        reopen()
        Grovs.track("cart_viewed")
        pumpUntil("shown once the server has in-app") { recorder.shown == listOf("c1") }
    }

    @Test
    fun `I5 a store url that appears makes an open_store campaign showable`() {
        inApp.storeUrl = null
        val storeCampaign = InAppCampaigns.campaign()
            .buttons(InAppCampaigns.button("s", "Store", InAppCampaigns.openStore())).build()
        val control = InAppCampaigns.campaign().id("control").triggers(InAppCampaigns.event("other")).build()
        start(storeCampaign, control)
        Grovs.track("cart_viewed")
        Grovs.track("other")
        pumpUntil("the control shows") { recorder.shown == listOf("control") }
        settle(2_000)
        assertEquals("skipped without a store", listOf("control"), recorder.shown)
        closeShown()

        inApp.storeUrl = "https://play.google.com/store/apps/details?id=io.grovs.test"
        reopen()
        Grovs.track("cart_viewed")
        pumpUntil("shown once a store url exists") { recorder.shown == listOf("control", "c1") }
    }

    @Test
    fun `I6 a batch answer without in_app does not sync`() {
        startSettled(c1)
        val before = syncRequests()
        contract.batchInApp = null
        contract.inApp = null
        Grovs.track("anything")
        flushEvents()
        assertTrue("the batch went out", reportedWhen("anything").isNotEmpty())
        idleFor(11_000)
        assertEquals(before, syncRequests())

        contract.inApp = inApp
        inApp.touch()
        Grovs.track("again")
        flushEvents()
        idleUntil("a sync once the batch answer carries new stamps", 11_000) { syncRequests() > before }
    }

    @Test
    fun `I7 the server clock decides, not the device clock`() {
        val endsSoon = InAppBackend.stamp(System.currentTimeMillis() + 3_600_000)
        val live = InAppCampaigns.campaign().id("live").schedule("2026-09-01T00:00:00Z", endAt = endsSoon).build()
        val control = InAppCampaigns.campaign().id("control").triggers(InAppCampaigns.event("other")).build()
        serverAhead(2 * 3_600_000)
        start(live, control)
        Grovs.track("cart_viewed")
        Grovs.track("other")
        pumpUntil("control shows") { recorder.shown == listOf("control") }
        settle(2_000)
        assertEquals("ended by server time", listOf("control"), recorder.shown)
    }
}
