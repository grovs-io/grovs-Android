package io.grovs.inapp.ui

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.grovs.inapp.FakeTimer
import io.grovs.inapp.InAppOutcome
import io.grovs.inapp.InAppPresentation
import io.grovs.inapp.model.InAppActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InAppHtmlSessionTest {
    private val outcomes = mutableListOf<InAppOutcome>()
    private val tracks = mutableListOf<Pair<String, JsonObject>>()
    private val attributes = mutableListOf<Pair<String, JsonElement>>()
    private val timers = mutableListOf<FakeTimer>()
    private val scripts = mutableListOf<String>()
    private val heights = mutableListOf<Double>()
    private var shownCount = 0
    private var revealed = 0
    private var asked = 0

    @Before
    fun setUp() {
        outcomes.clear(); tracks.clear(); attributes.clear(); timers.clear(); scripts.clear(); heights.clear()
        shownCount = 0; revealed = 0; asked = 0
    }

    private fun session(layout: String = "modal", canShow: (() -> Boolean)? = null): InAppHtmlSession {
        val presentation = InAppPresentation(
            campaign = InAppHtmlFixtures.campaign(layout),
            rendered = InAppHtmlFixtures.RENDERED,
            onShown = { shownCount++ },
            onTrack = { name, properties -> tracks += name to properties },
            onAttribute = { key, value -> attributes += key to value },
            canShow = canShow,
        )
        return InAppHtmlSession(
            presentation = presentation,
            schedule = { delay, work -> FakeTimer(delay, work).also { timers += it } },
            requestPush = { done -> asked++; done("granted") },
            evaluate = { scripts += it },
            onHeight = { heights += it },
            onReveal = { revealed++ },
            finish = { outcomes += it },
        ).also { it.start() }
    }

    private fun send(
        s: InAppHtmlSession, type: String, args: JsonObject = JsonObject(), token: String? = null,
        version: JsonElement = JsonPrimitive(1), mainFrame: Boolean = true,
    ) {
        val message = JsonObject()
        message.add("v", version)
        message.addProperty("token", token ?: s.token)
        message.addProperty("id", "$type-1")
        message.addProperty("type", type)
        message.add("args", args)
        s.receive(message.toString(), mainFrame)
    }

    private fun show(s: InAppHtmlSession) {
        send(s, "ready", obj("missing" to false))
        send(s, "height", obj("value" to 120))
    }

    private fun reply(): JsonObject {
        val script = scripts.last()
        assertTrue(script.startsWith("window.__grovsReply("))
        return JsonParser.parseString(script.removePrefix("window.__grovsReply(").removeSuffix(")")).asJsonObject
    }

    private fun opened(outcome: InAppOutcome?): String? =
        (outcome as? InAppOutcome.Clicked)?.takeIf { it.buttonId == null && it.action.type == InAppActionType.OPEN_URL }?.action?.url

    @Test
    fun ignoresAWrongTokenASubframeAnotherVersionAndAnUnknownType() {
        val s = session("full")
        send(s, "ready", obj("missing" to false), token = "forged")
        send(s, "ready", obj("missing" to false), mainFrame = false)
        send(s, "ready", obj("missing" to false), version = JsonPrimitive(2))
        send(s, "ready", obj("missing" to false), version = JsonPrimitive(true))
        send(s, "explode")
        s.receive("not an object", mainFrame = true)
        s.receive("[1,2]", mainFrame = true)
        assertEquals(0, shownCount)
        send(s, "ready", obj("missing" to false))
        assertEquals(1, shownCount)
        assertEquals(1, revealed)
    }

    @Test
    fun refusesAMessageThatIsNotStrictJson() {
        val s = session("full")
        s.receive("{\"v\":1,\"token\":\"${s.token}\",\"id\":\"1\",\"type\":\"ready\",\"args\":{\"missing\":false},\"pad\":NaN}", mainFrame = true)
        s.receive("{v:1,token:'${s.token}',id:'1',type:'ready',args:{missing:false}}", mainFrame = true)
        assertEquals(0, shownCount)
    }

    @Test
    fun deepNestingIsRefusedWithoutParsing() {
        val s = session("full")
        s.receive("[".repeat(200_000), mainFrame = true)
        s.receive("{\"v\":1,\"token\":\"${s.token}\",\"id\":\"1\",\"type\":\"ready\",\"args\":{\"missing\":false},\"pad\":" +
            "[".repeat(InAppHtmlSession.DEPTH_LIMIT) + "]".repeat(InAppHtmlSession.DEPTH_LIMIT) + "}", mainFrame = true)
        assertEquals(0, shownCount)
        // Brackets inside a string are text, not nesting.
        send(s, "ready", obj("missing" to false, "pad" to "[\\\"[".repeat(1_000)))
        assertEquals(1, shownCount)
    }

    @Test
    fun aMessageOverTheSizeLimitIsRefused() {
        val over = session("full")
        send(over, "ready", obj("missing" to false, "pad" to "x".repeat(InAppHtmlSession.MESSAGE_LIMIT)))
        assertEquals(0, shownCount)
        send(over, "ready", obj("missing" to false, "pad" to "x".repeat(InAppHtmlSession.MESSAGE_LIMIT - 1_000)))
        assertEquals(1, shownCount)
    }

    @Test
    fun aWholeFloatVersionIsAccepted() {
        val s = session("full")
        send(s, "ready", obj("missing" to false), version = JsonPrimitive(1.0))
        assertEquals(1, shownCount)
    }

    @Test
    fun ignoresInvalidArguments() {
        val s = session("full")
        send(s, "ready", obj("missing" to "no"))
        assertEquals(0, shownCount)
        send(s, "ready", obj("missing" to false))
        send(s, "track", obj("name" to 5))
        send(s, "open", obj("url" to listOf("https://a.example")))
        send(s, "setAttribute", obj("key" to 1, "value" to "x"))
        val noArgs = JsonObject().apply { addProperty("v", 1); addProperty("token", s.token); addProperty("id", "1"); addProperty("type", "close") }
        s.receive(noArgs.toString(), mainFrame = true)
        assertTrue(tracks.isEmpty())
        assertTrue(attributes.isEmpty())
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun staysHiddenUntilReadyAndAHeightThenShowsOnce() {
        val s = session()
        send(s, "ready", obj("missing" to false))
        send(s, "height", obj("value" to 0))
        assertEquals(0, shownCount)
        send(s, "height", obj("value" to 120))
        assertEquals(1, shownCount)
        assertEquals(1, revealed)
        assertEquals(listOf(0.0, 120.0), heights)
        send(s, "height", obj("value" to 140))
        assertEquals(1, shownCount)
        assertEquals(140.0, heights.last(), 0.0)
        assertTrue(timers.single { it.delay == InAppHtmlSession.DEADLINE_MILLIS }.cancelled)
    }

    @Test
    fun aFullPageShowsOnReadyAlone() {
        val s = session("full")
        send(s, "ready", obj("missing" to false))
        assertEquals(1, shownCount)
    }

    @Test
    fun aHeightBeforeReadyWaitsForReady() {
        val s = session("banner_top")
        send(s, "height", obj("value" to 80))
        assertEquals(0, shownCount)
        send(s, "ready", obj("missing" to false))
        assertEquals(1, shownCount)
    }

    @Test
    fun aNegativeOrNonNumberHeightIsIgnored() {
        val s = session()
        send(s, "height", obj("value" to -1))
        send(s, "height", obj("value" to "120"))
        assertTrue(heights.isEmpty())
    }

    @Test
    fun aMissingFieldSkipsTheMessageUnshownAndIsIgnoredOnceShown() {
        val s = session()
        send(s, "ready", obj("missing" to true))
        assertEquals(listOf<InAppOutcome>(InAppOutcome.Unshown("missing")), outcomes)
        send(s, "height", obj("value" to 120))
        assertEquals(0, shownCount)

        outcomes.clear()
        val shown = session()
        show(shown)
        send(shown, "ready", obj("missing" to true))
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun oneDeadlineThenEveryLateCallbackIsIgnored() {
        val s = session()
        val deadline = timers.single { it.delay == 5_000L }
        send(s, "ready", obj("missing" to false))
        send(s, "height", obj("value" to 0))
        deadline.work()
        assertEquals(listOf<InAppOutcome>(InAppOutcome.Unshown("timeout")), outcomes)
        send(s, "height", obj("value" to 120))
        send(s, "close")
        send(s, "track", obj("name" to "late", "properties" to emptyMap<String, Any>()))
        s.leave("app")
        assertEquals(0, shownCount)
        assertEquals(1, outcomes.size)
        assertTrue(tracks.isEmpty())
        assertTrue(s.isFinished)
    }

    @Test
    fun theDeadlineDoesNothingOnceShown() {
        val s = session()
        show(s)
        timers.single { it.delay == 5_000L }.work()
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun leavingBeforeItShowedIsUnshownAndAfterIsADismissal() {
        session().leave("app")
        assertEquals(InAppOutcome.Unshown("app"), outcomes.single())
        val shown = session()
        show(shown)
        shown.leave("app")
        assertEquals(InAppOutcome.Dismissed("app"), outcomes.last())
    }

    @Test
    fun abandoningEndsItWithNoOutcomeAndStopsTheDeadline() {
        val s = session()
        s.abandon()
        assertTrue(timers.single { it.delay == 5_000L }.cancelled)
        timers.single().work()
        s.leave("app")
        send(s, "ready", obj("missing" to false))
        assertTrue(outcomes.isEmpty())
        assertEquals(0, shownCount)
    }

    @Test
    fun isNotRevealedOnceTheAppNoLongerAllowsIt() {
        var allowed = true
        val s = session(canShow = { allowed })
        send(s, "ready", obj("missing" to false))
        allowed = false
        send(s, "height", obj("value" to 120))
        assertEquals(0, shownCount)
        assertEquals(0, revealed)
        assertEquals(InAppOutcome.Unshown("app"), outcomes.single())
        allowed = true
        send(s, "height", obj("value" to 130))
        assertEquals(0, shownCount)
        assertEquals(1, outcomes.size)
    }

    @Test
    fun callsBeforeTheMessageIsShownAreNotQueued() {
        val s = session()
        send(s, "close")
        send(s, "track", obj("name" to "early", "properties" to emptyMap<String, Any>()))
        send(s, "open", obj("url" to "https://a.example"))
        send(s, "setAttribute", obj("key" to "plan", "value" to "pro"))
        send(s, "requestPushPermission")
        assertTrue(outcomes.isEmpty())
        assertTrue(tracks.isEmpty())
        assertTrue(attributes.isEmpty())
        assertEquals(0, asked)
        assertEquals(obj("v" to 1, "id" to "requestPushPermission-1", "ok" to false, "error" to "not_shown"), reply())
    }

    @Test
    fun close() {
        val s = session()
        show(s)
        send(s, "close")
        assertEquals(InAppOutcome.Dismissed("close_button"), outcomes.single())
    }

    @Test
    fun trackKeepsTheMessageOpenAndRefusesReservedOrBadNames() {
        val s = session()
        show(s)
        for (name in listOf("in_app_clicked", "app_open", "journey_entered", "", "a".repeat(101))) {
            send(s, "track", obj("name" to name, "properties" to emptyMap<String, Any>()))
        }
        send(s, "track", obj("name" to "chose_plan", "properties" to "pro"))
        assertTrue(tracks.isEmpty())
        send(s, "track", obj("name" to "chose_plan", "properties" to mapOf("plan" to "pro")))
        send(s, "track", obj("name" to "a".repeat(100), "properties" to emptyMap<String, Any>()))
        // Measured in UTF-16 units, as JavaScript does: each of these is two.
        send(s, "track", obj("name" to "😀".repeat(51), "properties" to emptyMap<String, Any>()))
        send(s, "track", obj("name" to "😀".repeat(50), "properties" to null))
        assertEquals(listOf("chose_plan", "a".repeat(100), "😀".repeat(50)), tracks.map { it.first })
        assertEquals("pro", tracks.first().second.get("plan").asString)
        assertEquals(JsonObject(), tracks.last().second)
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun openFollowsTheLinkRuleThenCloses() {
        val s = session()
        show(s)
        for (url in listOf("javascript:alert(1)", "http://a.example", "/relative", "#top", "")) send(s, "open", obj("url" to url))
        assertTrue(outcomes.isEmpty())
        send(s, "open", obj("url" to " HTTPS://a.example/x\n"))
        assertEquals("https://a.example/x", opened(outcomes.single()))
        send(s, "open", obj("url" to "myapp://again"))
        assertEquals(1, outcomes.size)
    }

    @Test
    fun openEncodesARawLinkAndRefusesOneThatIsNoURLWithoutClosing() {
        val s = session()
        show(s)
        send(s, "open", obj("url" to "https://[::1"))
        assertTrue(outcomes.isEmpty())
        send(s, "open", obj("url" to "mailto:a@b.com?subject=Hi there"))
        assertEquals("mailto:a@b.com?subject=Hi%20there", opened(outcomes.single()))
    }

    @Test
    fun openIsRefusedOnlyWhenThePageWasNotActivated() {
        val refused = session()
        show(refused)
        send(refused, "open", obj("url" to "https://a.example", "active" to false))
        assertTrue(outcomes.isEmpty())
        send(refused, "open", obj("url" to "https://a.example/1", "active" to true))
        assertEquals("https://a.example/1", opened(outcomes.last()))

        val number = session()
        show(number)
        send(number, "open", obj("url" to "https://a.example/0", "active" to 0))
        assertEquals("only an explicit false refuses", "https://a.example/0", opened(outcomes.last()))

        val missing = session()
        show(missing)
        send(missing, "open", obj("url" to "https://a.example/2"))
        assertEquals("https://a.example/2", opened(outcomes.last()))
    }

    @Test
    fun setAttributeTakesScalarsAndNullOnly() {
        val s = session()
        show(s)
        send(s, "setAttribute", obj("key" to "", "value" to "x"))
        send(s, "setAttribute", obj("key" to "k".repeat(101), "value" to "x"))
        send(s, "setAttribute", obj("key" to "😀".repeat(51), "value" to "x"))
        send(s, "setAttribute", obj("key" to "list", "value" to listOf("a")))
        send(s, "setAttribute", obj("key" to "map", "value" to mapOf("a" to 1)))
        send(s, "setAttribute", obj("key" to "missing"))
        s.receive("{\"v\":1,\"token\":\"${s.token}\",\"id\":\"9\",\"type\":\"setAttribute\",\"args\":{\"key\":\"nan\",\"value\":NaN}}", mainFrame = true)
        assertTrue(attributes.isEmpty())
        send(s, "setAttribute", obj("key" to "plan", "value" to "pro"))
        send(s, "setAttribute", obj("key" to "k".repeat(100), "value" to 3.5))
        send(s, "setAttribute", obj("key" to "vip", "value" to true))
        send(s, "setAttribute", obj("key" to "gone", "value" to null))
        send(s, "setAttribute", obj("key" to "😀".repeat(50), "value" to 1))
        assertEquals(listOf("plan", "k".repeat(100), "vip", "gone", "😀".repeat(50)), attributes.map { it.first })
        assertEquals("pro", attributes.first().second.asString)
        assertTrue(attributes[3].second.isJsonNull)
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun requestPushPermissionAnswersWithTheState() {
        val s = session()
        show(s)
        send(s, "requestPushPermission")
        assertEquals(1, asked)
        assertEquals(obj("v" to 1, "id" to "requestPushPermission-1", "ok" to true, "result" to "granted"), reply())
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun aReplyAfterTheMessageEndedIsDropped() {
        var answer: ((String) -> Unit)? = null
        val s = InAppHtmlSession(
            presentation = InAppPresentation(campaign = InAppHtmlFixtures.campaign("full"), rendered = InAppHtmlFixtures.RENDERED),
            schedule = { delay, work -> FakeTimer(delay, work) },
            requestPush = { done -> answer = done },
            evaluate = { scripts += it }, onHeight = {}, onReveal = {}, finish = { outcomes += it },
        ).also { it.start() }
        send(s, "ready", obj("missing" to false))
        send(s, "requestPushPermission")
        s.leave("close_button")
        answer!!("granted")
        assertTrue(scripts.isEmpty())
    }

    @Test
    fun mainFrameNavigationsAreCancelledAndOnlyATappedOneOpens() {
        val s = session()
        assertFalse("embeds navigate freely", s.blocksNavigation(mainFrame = false, gesture = true, url = "https://youtube.com/embed/x"))
        assertTrue(s.blocksNavigation(mainFrame = true, gesture = true, url = "https://a.example/early"))
        assertTrue("nothing opens before it showed", outcomes.isEmpty())
        show(s)
        assertTrue(s.blocksNavigation(mainFrame = true, gesture = false, url = "https://a.example/script"))
        assertTrue(s.blocksNavigation(mainFrame = true, gesture = true, url = "http://a.example"))
        assertTrue(outcomes.isEmpty())
        assertTrue(s.blocksNavigation(mainFrame = true, gesture = true, url = "https://a.example/p"))
        assertEquals("https://a.example/p", opened(outcomes.single()))
    }

    @Test
    fun onlyTheFirstPageStartIsAllowed() {
        val s = session()
        assertFalse(s.blocksPageStart())
        assertTrue(s.blocksPageStart())
        assertTrue(s.blocksPageStart())
    }

    @Test
    fun onlyNetworkSchemesAreRemote() {
        assertTrue(InAppHtmlSession.isRemote("https"))
        assertTrue(InAppHtmlSession.isRemote("HTTP"))
        assertFalse(InAppHtmlSession.isRemote("data"))
        assertFalse(InAppHtmlSession.isRemote("about"))
        assertFalse(InAppHtmlSession.isRemote(null))
    }

    @Test
    fun aFreshTokenForEveryShow() {
        val first = session()
        val second = session()
        assertTrue(first.token != second.token)
        assertTrue(first.token.isNotBlank())
    }
}
