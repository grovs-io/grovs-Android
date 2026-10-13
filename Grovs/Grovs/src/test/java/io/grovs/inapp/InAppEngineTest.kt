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
        engine.peek(InAppEvent(InAppEventType.EVENT, "unused_event"), at)
        assertNull(engine.state.counts["unused_event"])
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

    private fun question(): JsonObject = campaign(
        """{"content":{"question":{"id":"q1","type":"stars","options":[],"comment":null,"thanks":null,"review_from":null}}}""",
    )

    @Test
    fun anAnswerIsKeptThroughASessionASyncAndARestart() {
        val engine = InAppShared.engine(firstCase, listOf(question()))
        assertEquals("c1", engine.track(cart, at).show)
        engine.answer("c1")
        engine.answer("c1")
        assertEquals(listOf("c1"), engine.state.answered)
        assertEquals(InAppReason.ANSWERED, engine.track(cart, at).reasons["c1"])
        engine.startSession()
        engine.markSynced(at)
        assertEquals(InAppReason.ANSWERED, engine.track(cart, at).reasons["c1"])

        val stored = InAppEngineState.fromJson(com.google.gson.JsonParser.parseString(engine.state.toJson().toString()))
        assertEquals(engine.state, stored)
        val restarted = InAppEngine(
            InAppShared.device(firstCase.device), listOf(question()), firstCase.globalCaps, at,
            newViewId = { "d9" }, state = stored,
        )
        val decision = restarted.track(cart, at)
        assertNull(decision.show)
        assertEquals(InAppReason.ANSWERED, decision.reasons["c1"])
    }

    @Test
    fun anAnswerIsNamedBeforeACapAndAfterACondition() {
        fun reason(changes: String): InAppReason? {
            val asking = InAppShared.merge(question(), InAppShared.obj(changes))
            val engine = InAppShared.engine(firstCase, listOf(asking))
            engine.track(cart, at)
            engine.answer("c1")
            return engine.track(cart, at).reasons["c1"]
        }
        assertEquals(InAppReason.ANSWERED, reason("""{"caps":{"session":1}}"""))
        assertEquals(InAppReason.CONDITION, reason("""{"conditions":[{"type":"language","op":"eq","value":"fr"}]}"""))
    }

    @Test
    fun aStateStoredByAnEarlierBuildIsStillRead() {
        val old = """{"views":[{"id":"v1","campaign":"c1","at":5,"global":true}],"session":["v1"],"counts":{"cart_viewed":2},"syncedAt":7}"""
        val state = InAppEngineState.fromJson(com.google.gson.JsonParser.parseString(old))!!
        assertEquals(listOf(InAppView("v1", "c1", 5, true)), state.views)
        assertEquals(listOf("v1"), state.session)
        assertEquals(mapOf("cart_viewed" to 2), state.counts)
        assertEquals(7L, state.syncedAt)
        assertEquals(emptyList<String>(), state.answered)
    }

    @Test
    fun aStateWrittenByIosReadsToo() {
        val ios = """{"views":[{"id":"v1","campaign":"c1","at":1790848800000.0,"global":false}],"session":[],"counts":{},"syncedAt":1790845200000,"answered":["c1"]}"""
        val state = InAppEngineState.fromJson(com.google.gson.JsonParser.parseString(ios))!!
        assertEquals(1790848800000L, state.views[0].at)
        assertEquals(listOf("c1"), state.answered)
    }

    @Test
    fun aDamagedListOfAnswersLosesOnlyTheAnswers() {
        for (damaged in listOf("5", "\"c1\"", "[1,2]", "{\"c1\":true}", "null")) {
            val stored = """{"views":[{"id":"v1","campaign":"c1","at":5,"global":true}],"session":["v1"],"counts":{},"syncedAt":7,"answered":$damaged}"""
            val state = InAppEngineState.fromJson(com.google.gson.JsonParser.parseString(stored))
            assertEquals(damaged, listOf("v1"), state!!.views.map { it.id })
            assertEquals(damaged, emptyList<String>(), state.answered)
        }
    }

    @Test
    fun anythingElseDamagedIsUnreadable() {
        val parts = listOf(
            """{"session":[],"counts":{},"syncedAt":7}""",
            """{"views":[{"id":"v1","campaign":"c1","at":"5","global":true}],"session":[],"counts":{},"syncedAt":7}""",
            """{"views":[],"session":[1],"counts":{},"syncedAt":7}""",
            """{"views":[],"session":[],"counts":{"x":1.5},"syncedAt":7}""",
            """{"views":[],"session":[],"counts":{}}""",
            "[]", "null",
        )
        for (written in parts) assertNull(written, InAppEngineState.fromJson(com.google.gson.JsonParser.parseString(written)))
        assertNull(InAppEngineState.fromJson(null))
    }
}
