package io.grovs.inapp

import com.google.gson.JsonParser
import io.grovs.GrovsInAppListenerAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppContractTest {

    @Test
    fun `the adapter lets every message show and ignores the rest`() {
        val listener = GrovsInAppListenerAdapter()
        assertTrue(listener.shouldShowInAppMessage("c1"))
        listener.onInAppMessageShown("c1")
        listener.onInAppMessageClicked("c1", null, null)
        listener.onInAppMessageDismissed("c1", "close_button")
        listener.onInAppMessageAnswered("c1", 4, null)
        listener.onInAppMessageFailed("c1", "no window")
    }

    @Test
    fun `the no draw presenter has nowhere to draw`() {
        assertFalse(NoDrawPresenter.present(dummyPresentation()) { throw AssertionError("never called") })
        NoDrawPresenter.dismiss()
    }

    @Test
    fun `settings start unpaused with nothing excluded`() {
        val settings = InAppSettings()
        assertNull(settings.listener)
        assertFalse(settings.paused)
        assertTrue(settings.excluded.isEmpty())
        assertFalse(settings.debug)
    }

    @Test
    fun `host values turn whole numbers into Long and keep fractions`() {
        val json = JsonParser.parseString(
            """{"stars":4,"price":2.5,"ok":true,"name":"Ana","none":null,"list":[1,"a"],"nested":{"n":1.0}}"""
        ).asJsonObject
        val map = InAppHostValues.toMap(json)
        assertEquals(4L, map["stars"])
        assertEquals(2.5, map["price"])
        assertEquals(true, map["ok"])
        assertEquals("Ana", map["name"])
        assertNull(map["none"])
        assertEquals(listOf(1L, "a"), map["list"])
        assertEquals(mapOf("n" to 1L), map["nested"])
        assertFalse(InAppHostValues.toProperties(json).containsKey("none"))
    }
}

private fun dummyPresentation(): InAppPresentation {
    val raw = JsonParser.parseString(
        """{"format":1,"id":"c1","revision":1,"created_at":"2026-09-01T00:00:00Z","priority":1,"template":"interstitial",
        "content":{"title":"Hello","message":"Welcome","buttons":[],"tap_action":null},
        "triggers":[{"type":"event","name":"cart_viewed"}],"conditions":[],
        "caps":{"session":null,"day":null,"week":null,"lifetime":null,"ignore_global":false},
        "schedule":{"start_at":"2026-09-01T00:00:00Z","end_at":null,"days":null,"from":null,"to":null},
        "delay_seconds":0,"delivery_mode":"device","variants":null,"control_group":null,"conversion":null}"""
    )
    val campaign = io.grovs.inapp.model.InAppDefinition.readCampaign(raw)!!
    return InAppPresentation(campaign, io.grovs.inapp.model.InAppRendered("Hello", "Welcome", emptyList(), emptyList()))
}
