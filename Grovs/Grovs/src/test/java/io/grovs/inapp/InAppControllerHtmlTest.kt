package io.grovs.inapp

import io.grovs.inapp.model.InAppAction
import io.grovs.inapp.model.InAppActionType
import io.grovs.inapp.model.InAppEvent
import io.grovs.inapp.model.InAppEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class InAppControllerHtmlTest {
    @Test
    fun `an open with no track before it is the click, with no button`() {
        val h = InAppHarness()
        h.start(listOf(InAppFixtures.campaign("""{"template":"html","content":{"html":"<p>Hi</p>","layout":"modal"}}""")))
        h.controller.onEvent(InAppEvent(InAppEventType.EVENT, "cart_viewed"))
        h.presenter.shown.single().onShown!!()
        h.presenter.finish(InAppOutcome.Clicked(null, InAppAction(InAppActionType.OPEN_URL, "https://a.example", null)))
        val clicked = h.reports.single { it.name == "in_app_clicked" }
        assertEquals("open_url", clicked.properties.get("action").asString)
        assertFalse(clicked.properties.has("button_id"))
        assertFalse(h.names().contains("in_app_dismissed"))
        assertEquals(listOf(Triple("c1", null as String?, null as Map<String, Any?>?)), h.listener.clicks)
    }
}
