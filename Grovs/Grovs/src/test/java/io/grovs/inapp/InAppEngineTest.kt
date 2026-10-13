package io.grovs.inapp

import com.google.gson.JsonObject
import io.grovs.inapp.model.InAppEvent
import io.grovs.inapp.model.InAppEventType
import io.grovs.inapp.model.InAppReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppEngineTest {
    private val at = 1790848800000L
    private val cart = InAppEvent(InAppEventType.EVENT, "cart_viewed")
    private val firstCase get() = InAppShared.cases().first()

    private fun campaign(changes: String): JsonObject = InAppShared.merge(InAppShared.baseCampaign(), InAppShared.obj(changes))

    @Test
    fun aTieGoesToTheSmallerIdByUnsignedUtf8Bytes() {
        val engine = InAppShared.engine(firstCase, listOf(campaign("""{"id":"é"}"""), campaign("""{"id":"z"}""")))
        val decision = engine.track(cart, at)
        assertEquals("z", decision.show)
        assertEquals(mapOf("é" to InAppReason.LOWER_PRIORITY), decision.reasons)
    }

    @Test
    fun eventNamesCompareExactlyWithNoUnicodeFolding() {
        val engine = InAppShared.engine(firstCase, listOf(campaign("""{"triggers":[{"type":"event","name":"café"}]}""")))
        assertEquals(InAppReason.NO_TRIGGER, engine.track(InAppEvent(InAppEventType.EVENT, "cafe\u0301"), at).reasons["c1"])
        assertEquals("c1", engine.track(InAppEvent(InAppEventType.EVENT, "café"), at).show)
    }

    @Test
    fun peekCountsAndDecidesButRecordsNothing() {
        val counted = campaign("""{"conditions":[{"type":"event_count","name":"cart_viewed","op":"gt","value":1}]}""")
        val engine = InAppShared.engine(firstCase, listOf(counted))
        assertNull(engine.peek(cart, at).show)
        val second = engine.peek(cart, at)
        assertEquals("c1", second.show)
        assertNull(second.view)
        assertEquals(2, engine.state.counts["cart_viewed"])
        assertTrue(engine.state.views.isEmpty())
        assertEquals("d1", engine.record("c1", at))
        assertEquals(listOf("d1"), engine.state.session)
        assertNull(engine.record("nope", at))
        assertEquals("c1", engine.recheck(cart, at).show)
        assertEquals(2, engine.state.counts["cart_viewed"])
    }

    @Test
    fun unsupportedListsUnreadableAndUnshowableCampaignsWithTheirRevision() {
        val unreadable = campaign("""{"id":"c2","revision":3,"content":{"question":{"type":"slider"}}}""")
        val device = InAppShared.obj("""{"unsupported_templates":["interstitial"]}""")
        val case = InAppShared.Case(
            firstCase.name, InAppShared.merge(firstCase.device, device), emptyList(), emptyList(),
            firstCase.globalCaps, firstCase.syncedAt, emptySet(),
        )
        val engine = InAppShared.engine(case, listOf(campaign("{}"), unreadable))
        assertEquals(listOf("c1" to 1, "c2" to 3), engine.unsupported)
        val decision = engine.track(cart, at)
        assertEquals(InAppReason.UNSUPPORTED, decision.reasons["c1"])
        assertEquals(InAppReason.UNSUPPORTED, decision.reasons["c2"])
    }

    @Test
    fun theWinnersDelayIsReturned() {
        val engine = InAppShared.engine(firstCase, listOf(campaign("""{"delay_seconds":5}""")))
        assertEquals(5, engine.track(cart, at).delay)
    }
}
