package io.grovs.e2e.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppCampaignsSchemaTest {

    private val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V6)
        .getSchema(javaClass.classLoader!!.getResourceAsStream("in-app-shared/definition.schema.json"))
    private val mapper = ObjectMapper()

    private fun assertValid(name: String, campaign: com.google.gson.JsonObject) {
        val errors = schema.validate(mapper.readTree(campaign.toString()))
        assertEquals("$name: $errors", emptySet<Any>(), errors)
    }

    @Test fun `every template the builder makes is a valid backend definition`() {
        val c = InAppCampaigns
        assertValid("interstitial", c.campaign().buttons(c.button("ok", "OK")).build())
        assertValid("cover", c.campaign().template("cover").build())
        assertValid("half_interstitial", c.campaign().template("half_interstitial").build())
        assertValid("header", c.campaign().template("header").build())
        assertValid("footer", c.campaign().template("footer").build())
        assertValid("alert", c.campaign().template("alert").buttons(c.button("ok", "OK")).build())
        listOf("cover_image", "interstitial_image", "half_interstitial_image").forEach {
            assertValid(it, c.campaign().template(it).images("https://img.example/p.png", null).build())
        }
        listOf("full", "modal", "banner_top", "banner_bottom").forEach {
            assertValid("html $it", c.campaign().html("<html><body>Hi</body></html>", it).build())
        }
    }

    @Test fun `questions, actions, caps, schedules and conditions are valid`() {
        val c = InAppCampaigns
        assertValid("stars", c.campaign().question(c.stars(reviewFrom = 4, comment = true, split = 3, low = "Why?", high = "Great!", thanks = "Thanks")).build())
        assertValid("choice", c.campaign().question(c.choice("a" to "Alpha", "b" to "Beta", comment = false, thanks = null)).build())
        assertValid("actions", c.campaign().buttons(c.button("u", "Open", c.openUrl("https://grovs.io/x")), c.button("s", "Store", c.openStore())).tapAction(c.keyValue(mapOf("k" to "v"))).build())
        assertValid("permission", c.campaign().buttons(c.button("p", "Allow", c.requestPushPermission())).build())
        assertValid("rating", c.campaign().buttons(c.button("r", "Rate", c.requestAppRating())).build())
        assertValid("caps", c.campaign().caps(session = 1, day = 2, week = 3, lifetime = 4, ignoreGlobal = true).build())
        assertValid("schedule", c.campaign().schedule("2026-09-01T00:00:00Z", "2026-12-01T00:00:00Z", listOf(1, 2), "09:00", "17:00").build())
        assertValid("triggers", c.campaign().triggers(c.appOpen(), c.screen("Pricing"), c.event("cart_viewed", c.filter("plan", "eq", "pro"))).build())
        assertValid("conditions", c.campaign().condition(c.raw("""{"type":"app_version","op":"gt","value":"1.0.0"}""")).build())
        assertValid("not dismissible", c.campaign().dismissible(false).buttons(c.button("ok", "OK")).build())
    }

    @Test fun `the schema check refuses a definition the backend would refuse`() {
        val c = InAppCampaigns
        val broken = c.campaign().question(c.stars()).buttons(c.button("ok", "OK")).build()
        assertTrue("a question with buttons must fail", schema.validate(mapper.readTree(broken.toString())).isNotEmpty())
    }
}
