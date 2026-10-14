package io.grovs.e2e.inapp

import com.google.gson.JsonPrimitive
import io.grovs.Grovs
import io.grovs.e2e.contract.InAppBackend.SyncMode
import io.grovs.e2e.contract.InAppCampaigns
import io.grovs.e2e.contract.InAppCampaigns.appOpen
import io.grovs.e2e.contract.InAppCampaigns.event
import io.grovs.e2e.contract.InAppCampaigns.filter
import io.grovs.e2e.contract.InAppCampaigns.raw
import io.grovs.e2e.contract.InAppCampaigns.screen
import io.grovs.inapp.InAppSynced
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
internal class InAppTriggersRulesE2ETest : InAppTestBase() {

    private val defaultLocale = Locale.getDefault()

    @After
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    private fun viewedIds() = reportedWhen("in_app_viewed").map { it.getJSONObject("properties").getString("campaign_id") }

    // cases.json: "an event trigger shows the campaign", "event names exact"
    @Test
    fun `I9 an event trigger matches the exact name only`() {
        start(InAppCampaigns.campaign().build())
        Grovs.track("Cart_Viewed")
        settle(3_000)
        assertTrue(recorder.shown.isEmpty())
        Grovs.track("cart_viewed")
        pumpUntil("exact name shows") { recorder.shown == listOf("c1") }
    }

    // cases.json: "a screen view trigger matches the name without regard to case, or any screen when unnamed"
    @Test
    fun `I9 a screen trigger matches the screen name without case`() {
        start(InAppCampaigns.campaign().triggers(screen("pricing")).build())
        Grovs.trackScreenView("Other")
        settle(3_000)
        assertTrue(recorder.shown.isEmpty())
        Grovs.trackScreenView("Pricing")
        pumpUntil("screen shows") { recorder.shown == listOf("c1") }
    }

    // cases.json: "a screen view trigger matches the name without regard to case, or any screen when unnamed"
    @Test
    fun `I9 an unnamed screen trigger matches any screen`() {
        start(InAppCampaigns.campaign().triggers(screen(null)).build())
        Grovs.trackScreenView("Anything")
        pumpUntil("any screen shows") { recorder.shown == listOf("c1") }
    }

    // cases.json: "every filter operator"
    @Test
    fun `I9 an event filter must match the properties`() {
        start(InAppCampaigns.campaign().triggers(event("cart_viewed", filter("plan", "eq", "pro"))).build())
        Grovs.track("cart_viewed", mapOf("plan" to "free"))
        settle(3_000)
        assertTrue(recorder.shown.isEmpty())
        Grovs.track("cart_viewed", mapOf("plan" to "pro"))
        pumpUntil("matching filter shows") { recorder.shown == listOf("c1") }
    }

    // app_open is raised once per SDK session
    @Test
    fun `I9 app_open shows once per SDK session`() {
        start(
            InAppCampaigns.campaign().triggers(appOpen()).build(),
            InAppCampaigns.campaign().id("ctl").build(),
        )
        pumpUntil("app_open shows") { recorder.shown == listOf("c1") }
        closeShown()

        reopen()
        Grovs.track("cart_viewed")
        pumpUntil("control shows after the same-session foreground") { recorder.shown.size >= 2 }
        settle(2_000)
        assertEquals("no second app_open in the same session", listOf("c1", "ctl"), recorder.shown)
        closeShown()

        restart()
        pumpUntil("app_open shows again in the new session") { recorder.shown.count { it == "c1" } == 2 }
    }

    // cases.json: "the highest priority wins and a tie goes to the older campaign",
    // "a campaign that loses on priority is not counted as viewed"
    @Test
    fun `I8 a higher priority wins and the loser is not counted as viewed`() {
        start(
            InAppCampaigns.campaign().id("low").priority(1).build(),
            InAppCampaigns.campaign().id("high").priority(5).build(),
        )
        Grovs.track("cart_viewed")
        pumpUntil("the winner") { recorder.shown.isNotEmpty() }
        closeShown()
        assertEquals(listOf("high"), recorder.shown)
        assertEquals(listOf("high"), viewedIds())
    }

    // cases.json: "the highest priority wins and a tie goes to the older campaign"
    @Test
    fun `I8 equal priority goes to the older campaign`() {
        start(
            InAppCampaigns.campaign().id("newer").createdAt("2026-09-02T00:00:00Z").build(),
            InAppCampaigns.campaign().id("older").createdAt("2026-09-01T00:00:00Z").build(),
        )
        Grovs.track("cart_viewed")
        pumpUntil("the winner") { recorder.shown.isNotEmpty() }
        assertEquals(listOf("older"), recorder.shown)
    }

    // cases.json: "a tie on priority and age goes to the smaller id"
    @Test
    fun `I8 equal priority and age goes to the smaller id`() {
        start(
            InAppCampaigns.campaign().id("b").build(),
            InAppCampaigns.campaign().id("a").build(),
        )
        Grovs.track("cart_viewed")
        pumpUntil("the winner") { recorder.shown.isNotEmpty() }
        assertEquals(listOf("a"), recorder.shown)
    }

    // cases.json: "a session cap resets with a new session"
    @Test
    fun `I11 a session cap stops the next show and resets with a new session`() {
        start(InAppCampaigns.campaign().caps(session = 1).build())
        Grovs.track("cart_viewed")
        pumpUntil("first") { recorder.shown.size == 1 }
        closeShown()
        Grovs.track("cart_viewed")
        settle(3_000)
        assertEquals(1, recorder.shown.size)

        restart()
        Grovs.track("cart_viewed")
        pumpUntil("again in a new session") { recorder.shown.size == 2 }
    }

    // cases.json: "a session cap resets with a new session"
    @Test
    fun `I11 a session cap resets after the app was away for more than 30 minutes`() {
        start(InAppCampaigns.campaign().caps(session = 1).build())
        Grovs.track("cart_viewed")
        pumpUntil("first") { recorder.shown.size == 1 }
        closeShown()

        reopen()
        Grovs.track("cart_viewed")
        settle(3_000)
        assertEquals("a short absence keeps the session", 1, recorder.shown.size)

        reopenAfterBackground(minutesAway = 31)
        Grovs.track("cart_viewed")
        pumpUntil("again in the new session") { recorder.shown.size == 2 }
    }

    // cases.json: "the global session cap spans campaigns, except those that opt out"
    @Test
    fun `I11 the global session cap applies unless ignore_global`() {
        inApp.globalCaps = 1 to 10
        start(
            InAppCampaigns.campaign().id("a").triggers(event("e1")).build(),
            InAppCampaigns.campaign().id("b").triggers(event("e2")).build(),
            InAppCampaigns.campaign().id("free").triggers(event("e3")).caps(ignoreGlobal = true).build(),
        )
        Grovs.track("e1")
        pumpUntil("a") { recorder.shown == listOf("a") }
        closeShown()
        Grovs.track("e2")
        settle(3_000)
        assertEquals("global cap blocks b", listOf("a"), recorder.shown)
        Grovs.track("e3")
        pumpUntil("ignore_global shows") { recorder.shown == listOf("a", "free") }
    }

    // cases.json: "a lifetime cap counts every view on this device"
    @Test
    fun `I11 lifetime caps survive a restart`() {
        start(
            InAppCampaigns.campaign().caps(lifetime = 1).build(),
            InAppCampaigns.campaign().id("ctl").triggers(event("other")).build(),
        )
        Grovs.track("cart_viewed")
        pumpUntil("first") { recorder.shown == listOf("c1") }
        closeShown()

        restart()
        Grovs.track("cart_viewed")
        Grovs.track("other")
        pumpUntil("the control shows after the restart") { recorder.shown.size >= 2 }
        settle(2_000)
        assertEquals("c1 is used up for good", listOf("c1", "ctl"), recorder.shown)
    }

    // cases.json: "versions compare part by part, not as text"
    @Test
    fun `I10 an app_version condition compares versionName part by part`() {
        shadowOf(application.packageManager).getInternalMutablePackageInfo(application.packageName)!!.versionName = "2.0.9"
        start(
            InAppCampaigns.campaign().id("newer")
                .condition(raw("""{"type":"app_version","op":"gt","value":"2.0.10"}""")).build(),
            InAppCampaigns.campaign().id("older").triggers(event("other"))
                .condition(raw("""{"type":"app_version","op":"lt","value":"2.0.10"}""")).build(),
        )
        Grovs.track("cart_viewed")
        settle(3_000)
        assertTrue("2.0.9 is not above 2.0.10", recorder.shown.isEmpty())
        Grovs.track("other")
        pumpUntil("2.0.9 is below 2.0.10") { recorder.shown == listOf("older") }
    }

    // cases.json: "every device condition must pass"
    @Test
    fun `I10 a user attribute condition reads the host attributes`() {
        start(InAppCampaigns.campaign().condition(raw("""{"type":"user_attribute","key":"plan","op":"eq","value":"pro"}""")).build())
        Grovs.track("cart_viewed")
        settle(3_000)
        assertTrue(recorder.shown.isEmpty())
        Grovs.attributes = mapOf("plan" to "pro")
        settle(2_000)
        Grovs.track("cart_viewed")
        pumpUntil("attribute now matches") { recorder.shown == listOf("c1") }
    }

    // cases.json: "an event count includes the event being tracked"
    @Test
    fun `I10 an event_count condition counts the events seen`() {
        start(InAppCampaigns.campaign().condition(raw("""{"type":"event_count","name":"cart_viewed","op":"gt","value":1}""")).build())
        Grovs.track("cart_viewed")
        settle(3_000)
        assertTrue("one is not more than one", recorder.shown.isEmpty())
        Grovs.track("cart_viewed")
        pumpUntil("second event shows") { recorder.shown == listOf("c1") }
    }

    // cases.json: "a portrait-only message shows in portrait and a landscape-only one does not"
    @Test
    fun `I10 content held to the other orientation does not show`() {
        start(
            InAppCampaigns.campaign().id("land").orientation("landscape").build(),
            InAppCampaigns.campaign().id("port").triggers(event("other")).orientation("portrait").build(),
        )
        Grovs.track("cart_viewed")
        settle(3_000)
        assertTrue(recorder.shown.isEmpty())
        Grovs.track("other")
        pumpUntil("control: the portrait one shows") { recorder.shown == listOf("port") }
    }

    // cases.json: "nothing shows when the last sync is more than 48 hours old", "a sync resets the 48 hour clock"
    @Test
    fun `I12 more than 48 hours since the last sync shows nothing`() {
        start(InAppCampaigns.campaign().build())
        // Seam: moving the stored server offset 49 h forward and failing the next sync stands in for a phone
        // that stayed offline for two days after its last good sync.
        val synced = InAppSynced.load(store())!!
        synced.copy(clockOffset = synced.clockOffset + 49L * 3_600_000).save(store())
        inApp.mode = SyncMode.FAIL
        restart()
        Grovs.track("cart_viewed")
        settle(3_000)
        assertTrue("stale campaigns never show", recorder.shown.isEmpty())

        inApp.mode = SyncMode.UNCHANGED_WHEN_SAME
        reopen()
        Grovs.track("cart_viewed")
        pumpUntil("control: fresh again after a good sync") { recorder.shown == listOf("c1") }
    }

    // cases.json: "a definition the engine cannot read is unsupported, and unknown fields are ignored"
    @Test
    fun `I13 a campaign with unknown fields still shows`() {
        start(
            InAppCampaigns.campaign()
                .set("brand_new_field", JsonPrimitive("x"))
                .set("content.extra", JsonPrimitive(1))
                .build(),
        )
        Grovs.track("cart_viewed")
        pumpUntil("unknown fields ignored") { recorder.shown == listOf("c1") }
    }

    // cases.json: "a definition the engine cannot read is unsupported, and unknown fields are ignored" and "future_format"
    @Test
    fun `I13 an unsupported definition never shows and never crashes`() {
        start(
            InAppCampaigns.campaign().id("v").set("variants", raw("""{"a":1}""")).build(),
            InAppCampaigns.campaign().id("f2").set("format", JsonPrimitive(2)).build(),
            InAppCampaigns.campaign().id("ok").triggers(event("other")).build(),
        )
        Grovs.track("cart_viewed")
        settle(3_000)
        assertTrue(recorder.shown.isEmpty())
        Grovs.track("other")
        pumpUntil("a supported one still works") { recorder.shown == listOf("ok") }
    }

    // lowercasing uses Locale.ROOT, never the default locale
    @Test
    fun `I14 a Turkish default locale changes nothing`() {
        Locale.setDefault(Locale("tr", "TR"))
        start(
            InAppCampaigns.campaign().id("filter").triggers(event("cart_viewed", filter("item", "eq", "i"))).build(),
            InAppCampaigns.campaign().id("screen").triggers(screen("info")).build(),
        )
        Grovs.track("cart_viewed", mapOf("item" to "I"))
        pumpUntil("the filter matches I with i") { recorder.shown == listOf("filter") }
        closeShown()
        Grovs.trackScreenView("INFO")
        pumpUntil("the screen name matches INFO with info") { recorder.shown == listOf("filter", "screen") }
    }

    // numbers never depend on the default locale
    @Test
    fun `I14 a comma decimal locale changes nothing`() {
        Locale.setDefault(Locale.GERMANY)
        start(InAppCampaigns.campaign().triggers(event("cart_viewed", filter("price", "gt", 1.2))).build())
        Grovs.track("cart_viewed", mapOf("price" to 1.0))
        settle(3_000)
        assertTrue(recorder.shown.isEmpty())
        Grovs.track("cart_viewed", mapOf("price" to 1.5))
        pumpUntil("1.5 is above 1.2") { recorder.shown == listOf("c1") }
    }
}
