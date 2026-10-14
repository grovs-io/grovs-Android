package io.grovs.e2e.inapp

import io.grovs.Grovs
import io.grovs.e2e.contract.InAppCampaigns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
internal class InAppReportingE2ETest : InAppTestBase() {

    // The 22 event names the backend reserves.
    private val reservedNames = listOf(
        "app_open", "custom", "in_app_answered", "in_app_clicked", "in_app_commented", "in_app_dismissed", "in_app_held",
        "in_app_viewed", "install", "journey_entered", "journey_held", "journey_path", "open", "push_delivered", "push_held",
        "push_open", "reactivation", "reinstall", "screen_view", "time_spent", "user_referred", "view",
    )

    @Test
    fun `I59 every in-app event carries campaign_id and an integer revision`() {
        start(InAppCampaigns.campaign().revision(3).buttons(InAppCampaigns.button("ok", "OK", InAppCampaigns.close())).build())
        Grovs.track("cart_viewed")
        pumpUntil("shown") { recorder.shown.isNotEmpty() }
        tapButton("ok")
        val events = reportedWhen("in_app_viewed") + reportedWhen("in_app_clicked")
        assertEquals(2, events.size)
        events.forEach {
            val p = it.getJSONObject("properties")
            assertEquals("c1", p.getString("campaign_id"))
            val revision = p.get("revision")
            assertTrue("revision $revision", revision is Int || revision is Long)
            assertEquals(3, (revision as Number).toInt())
        }
    }

    @Test
    fun `I59 a dismissal event carries campaign_id, an integer revision and the reason`() {
        start(InAppCampaigns.campaign().revision(3).build())
        Grovs.track("cart_viewed")
        pumpUntil("shown") { recorder.shown.isNotEmpty() }
        closeShown()
        val p = reportedWhen("in_app_dismissed").single().getJSONObject("properties")
        assertEquals("c1", p.getString("campaign_id"))
        assertTrue("revision ${p.get("revision")}", p.get("revision").let { it is Int || it is Long })
        assertEquals(3, p.getInt("revision"))
        assertEquals(recorder.dismissed.single().second, p.getString("reason"))
        assertEquals("close_button", p.getString("reason"))
    }

    @Test
    fun `I59 the view event carries the template and an event id`() {
        start(InAppCampaigns.campaign().template("cover").build())
        Grovs.track("cart_viewed")
        pumpUntil("shown") { recorder.shown.isNotEmpty() }
        val viewed = reportedWhen("in_app_viewed").single()
        assertEquals("cover", viewed.getJSONObject("properties").getString("template"))
        assertTrue(viewed.optString("event_id").isNotBlank())
    }

    @Test
    fun `I59 clicked carries action and button_id`() {
        start(InAppCampaigns.campaign().buttons(InAppCampaigns.button("ok", "OK", InAppCampaigns.close())).build())
        Grovs.track("cart_viewed")
        pumpUntil("shown") { recorder.shown.isNotEmpty() }
        tapButton("ok")
        val click = reportedWhen("in_app_clicked").single().getJSONObject("properties")
        assertEquals("close", click.getString("action"))
        assertEquals("ok", click.getString("button_id"))
    }

    @Test
    fun `I60 the host cannot track any of the 22 reserved names`() {
        start(InAppCampaigns.campaign().triggers(InAppCampaigns.event("in_app_viewed")).build())
        reservedNames.forEach { Grovs.track(it, mapOf("campaign_id" to "c1")) }
        Grovs.track("allowed_one")
        assertEquals("control: a normal name goes out", 1, reportedWhen("allowed_one").size)
        assertTrue(contract.batchEvents().none { it.optString("event_name") in reservedNames })
        settle(2_000)
        assertTrue("a host in_app_viewed is not a trigger", recorder.shown.isEmpty())
    }
}
