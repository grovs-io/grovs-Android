package io.grovs.e2e.inapp

import android.view.View
import android.widget.TextView
import io.grovs.Grovs
import io.grovs.R
import io.grovs.e2e.contract.InAppCampaigns
import io.grovs.e2e.contract.InAppCampaigns.button
import io.grovs.e2e.contract.InAppCampaigns.event
import io.grovs.e2e.contract.InAppCampaigns.openUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
internal class InAppPersonalizationE2ETest : InAppTestBase() {

    private fun text(id: Int) = view<TextView>(id).text.toString()

    private fun buttonText(id: String) = (root().findViewWithTag<View>("grovs-in-app-button-$id") as TextView).text.toString()

    // fields work in title, message and button text; a fallback fills an absent value
    @Test
    fun `I26 user values fill title, message and button text`() {
        Grovs.attributes = mapOf("first_name" to "Ana")
        showOne(
            InAppCampaigns.campaign().title("Hi {{user.first_name}}").message("Plan {{user.plan | default:\"free\"}}")
                .buttons(button("ok", "Thanks {{user.first_name}}")).build(),
        )
        assertEquals("Hi Ana", text(R.id.grovs_in_app_title))
        assertEquals("Plan free", text(R.id.grovs_in_app_message))
        assertEquals("Thanks Ana", buttonText("ok"))
    }

    // "event is the triggering event's properties"
    @Test
    fun `I26 event values fill the text`() {
        start(InAppCampaigns.campaign().title("Item {{event.sku}}").build())
        Grovs.track("cart_viewed", mapOf("sku" to "A-1"))
        pumpUntil("the message to show") { recorder.shown.size == 1 }
        assertEquals("Item A-1", text(R.id.grovs_in_app_title))
    }

    // shared README Links: percent-encoded, uppercase hex, space is %20 never +; text stays plain
    @Test
    fun `I26 link values are percent-encoded`() {
        val value = "a&b c é"
        Grovs.attributes = mapOf("q" to value)
        showOne(
            InAppCampaigns.campaign().title("Search {{user.q}}")
                .buttons(button("go", "Go", openUrl("https://grovs.io/s?q={{user.q}}"))).build(),
        )
        assertEquals("Search $value", text(R.id.grovs_in_app_title))
        tapButton("go")
        val opened = shadowOf(application).nextStartedActivity
        assertNotNull("the link opened", opened)
        assertEquals("https://grovs.io/s?q=a%26b%20c%20%C3%A9", opened.dataString)
    }

    // shared README Links: a fallback is encoded like a value
    @Test
    fun `I26 a fallback in a link is percent-encoded`() {
        showOne(
            InAppCampaigns.campaign().buttons(button("go", "Go", openUrl("https://grovs.io/s?q={{user.q | default:\"x y\"}}"))).build(),
        )
        tapButton("go")
        assertEquals("https://grovs.io/s?q=x%20y", shadowOf(application).nextStartedActivity.dataString)
    }

    // "no fallback fails" personalization, so the campaign is skipped
    @Test
    fun `I26 a missing value without a default skips the campaign`() {
        start(
            InAppCampaigns.campaign().id("needs").title("Hi {{user.nickname}}").build(),
            InAppCampaigns.campaign().id("control").triggers(event("other")).build(),
        )
        Grovs.track("cart_viewed"); settle(3_000)
        assertTrue(recorder.shown.isEmpty())
        Grovs.track("other"); pumpUntil("control") { recorder.shown == listOf("control") }
    }
}
