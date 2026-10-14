package io.grovs.inapp

import com.google.gson.JsonPrimitive
import io.grovs.inapp.InAppFixtures.campaign
import io.grovs.inapp.InAppFixtures.json
import io.grovs.inapp.model.InAppAction
import io.grovs.inapp.model.InAppActionType
import io.grovs.inapp.model.InAppAnswer
import io.grovs.inapp.model.InAppEvent
import io.grovs.inapp.model.InAppEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InAppControllerAnswersTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val cart = InAppEvent(InAppEventType.EVENT, "cart_viewed")
    private fun screen(name: String) = InAppEvent(InAppEventType.SCREEN_VIEW, name)

    private fun asking(type: String = "stars", question: String = "{}", changes: String = "{}"): com.google.gson.JsonObject {
        val options = if (type == "choice") """[{"id":"search","text":"Search"},{"id":"friend","text":"A friend"}]""" else "[]"
        val raw = InAppFixtures.merge(
            json("""{"id":"q1","type":"$type","options":$options,"comment":null,"thanks":null,"review_from":null}"""),
            json(question),
        )
        val withQuestion = json("""{"revision":3,"content":{"question":$raw}}""")
        return campaign(InAppFixtures.merge(withQuestion, json(changes)).toString())
    }

    private fun answer(h: InAppHarness, value: InAppAnswer) = h.presenter.shown.last().onAnswer!!(value)

    private fun html(changes: String = "{}") = campaign(
        InAppFixtures.merge(json("""{"template":"html","content":{"html":"<p>Hi</p>","layout":"modal"}}"""), json(changes)).toString()
    )

    // Test messages, IOS/InAppController.swift:375-430

    @Test
    fun `shows a test message as it is and counts and reports nothing`() {
        val h = InAppHarness()
        h.start(emptyList(), testMessages(campaign("""{"id":"t1","triggers":[{"type":"event","name":"never"}],"caps":{"lifetime":1}}""")))
        assertEquals(listOf("t1"), h.presenter.ids)
        assertTrue(h.presenter.shown.single().isTest)
        h.presenter.finish(InAppOutcome.Clicked(null, InAppAction(InAppActionType.CLOSE, null, null)))
        assertTrue(h.reports.isEmpty())
        assertEquals(0, h.kept()?.state?.views?.size ?: 0)
        assertTrue(h.listener.clicks.isEmpty())
    }

    @Test
    fun `shows a test message after the message that is on screen`() {
        val h = InAppHarness()
        h.start(listOf(campaign()))
        h.controller.onEvent(cart)
        h.respond(listOf(campaign()), InAppFixtures.merge(testMessages(campaign("""{"id":"t1"}""")), json("""{"campaigns_changed_at":"c2"}""")).asJsonObject)
        h.controller.onForeground()
        assertEquals(listOf("c1"), h.presenter.ids)
        h.presenter.finish(InAppOutcome.Dismissed("close_button"))
        assertEquals(listOf("c1", "t1"), h.presenter.ids)
    }

    @Test
    fun `keeps a test message that arrived in the background and shows it on return`() {
        val h = InAppHarness()
        h.isActive = false
        h.start(emptyList(), testMessages(campaign("""{"id":"t1"}""")))
        assertEquals(emptyList<String>(), h.presenter.ids)
        h.isActive = true
        h.unchanged()
        h.controller.onForeground()
        assertEquals(listOf("t1"), h.presenter.ids)
    }

    @Test
    fun `holds a test message while the host has paused or is on an excluded screen`() {
        val h = InAppHarness()
        h.controller.pause()
        h.controller.setExcludedScreens(listOf("checkout"))
        h.controller.onEvent(screen("Checkout"))
        h.start(emptyList(), testMessages(campaign("""{"id":"t1"}""")))
        assertEquals(emptyList<String>(), h.presenter.ids)
        h.controller.resume()
        assertEquals(emptyList<String>(), h.presenter.ids)
        h.controller.onEvent(screen("Home"))
        assertEquals(listOf("t1"), h.presenter.ids)
    }

    @Test
    fun `waits for the pictures of a test message with one timer at a time and gives up after ten tries`() {
        val h = InAppHarness(imageCache = InAppImages(temp.root, loader = { _, done -> done(null) }, decodes = { true }))
        val picture = """{"image":{"portrait":"https://cdn.example.com/t.png"}}"""
        h.start(emptyList(), testMessages(campaign("""{"id":"t1","content":$picture}""")))
        h.controller.resume()
        assertEquals(1, h.timers.count { it.delay == InAppController.TEST_RETRY_MS && !it.cancelled })

        // The first try started the first timer; each of the next nine starts one more, the eleventh gives up.
        repeat(InAppController.TEST_TRIES) {
            h.timers.last { it.delay == InAppController.TEST_RETRY_MS }.work()
        }
        assertEquals(InAppController.TEST_TRIES, h.timers.count { it.delay == InAppController.TEST_RETRY_MS })
        h.controller.onEvent(screen("Home"))
        assertEquals("dropped, so no new timer", InAppController.TEST_TRIES, h.timers.count { it.delay == InAppController.TEST_RETRY_MS })
        assertEquals(emptyList<String>(), h.presenter.ids)
    }

    @Test
    fun `keeps a test that still waits when another arrives and shows both`() {
        val h = InAppHarness()
        h.start(listOf(campaign()))
        h.controller.onEvent(cart)
        for ((id, stamp) in listOf("t1" to "c2", "t2" to "c3")) {
            h.respond(listOf(campaign()), InAppFixtures.merge(testMessages(campaign("""{"id":"$id"}""")), json("""{"campaigns_changed_at":"$stamp"}""")).asJsonObject)
            h.controller.onForeground()
        }
        repeat(3) { h.presenter.finish(InAppOutcome.Dismissed("close_button")) }
        assertEquals(listOf("c1", "t1", "t2"), h.presenter.ids)
    }

    @Test
    fun `a waiting test keeps its pictures when the campaigns change`() {
        val h = InAppHarness(imageCache = InAppImages(temp.root, loader = { _, done -> done(byteArrayOf(1)) }, decodes = { true }))
        val picture = """{"image":{"portrait":"https://cdn.example.com/t.png"}}"""
        h.start(listOf(campaign()))
        h.controller.onEvent(cart)
        h.respond(listOf(campaign()), InAppFixtures.merge(testMessages(campaign("""{"id":"t1","content":$picture}""")), json("""{"campaigns_changed_at":"c2"}""")).asJsonObject)
        h.controller.onForeground()
        h.respond(listOf(campaign("""{"id":"c9"}""")), json("""{"campaigns_changed_at":"c3"}"""))
        h.controller.onForeground()
        h.presenter.finish(InAppOutcome.Dismissed("close_button"))
        assertEquals(listOf("c1", "t1"), h.presenter.ids)
    }

    @Test
    fun `a test message is filled with the current user and an empty app open event`() {
        val h = InAppHarness()
        h.start(emptyList(), testMessages(campaign("""{"id":"t1","content":{"title":"Hi {{ user.first_name }} {{ event.item | default: \"none\" }}"}}""")))
        val shown = h.presenter.shown.single()
        assertEquals("Hi Ana none", shown.rendered.title)
        assertEquals("Ana", shown.user.get("first_name").asString)
        assertEquals(0, shown.event.size())
    }

    // Answers, IOS/InAppController.swift:316-323, 432-470

    @Test
    fun `an answer is reported and recorded at the tap before the message closes`() {
        val h = InAppHarness()
        h.start(listOf(asking()))
        h.controller.onEvent(cart)
        answer(h, InAppAnswer.Stars(4))

        assertEquals(listOf("in_app_viewed", "in_app_answered"), h.names())
        val report = h.reports.last()
        assertEquals("c1", report.properties.get("campaign_id").asString)
        assertEquals(JsonPrimitive(3), report.properties.get("revision"))
        assertEquals("q1", report.properties.get("question_id").asString)
        assertEquals("stars", report.properties.get("type").asString)
        assertEquals(JsonPrimitive(4), report.properties.get("value"))
        assertEquals(5, report.properties.size())
        assertNotEquals(h.reports.first().id, report.id)
        assertEquals(listOf("c1"), h.kept()!!.state.answered)
        assertTrue("the host is told when the message has closed", h.listener.answers.isEmpty())
    }

    @Test
    fun `a choice is reported by the id of its option`() {
        val h = InAppHarness()
        h.start(listOf(asking("choice")))
        h.controller.onEvent(cart)
        answer(h, InAppAnswer.Option("friend"))
        h.presenter.finish(InAppOutcome.Answered(InAppAnswer.Option("friend"), null))
        assertEquals("choice", h.reports.last().properties.get("type").asString)
        assertEquals("friend", h.reports.last().properties.get("value").asString)
        assertEquals("friend", h.listener.answers.single().second)
    }

    @Test
    fun `a comment is reported at Send and not again when the message closes`() {
        val h = InAppHarness()
        h.start(listOf(asking()))
        h.controller.onEvent(cart)
        answer(h, InAppAnswer.Stars(2))
        h.presenter.shown.last().onComment!!("Too slow")
        assertEquals(listOf("in_app_viewed", "in_app_answered", "in_app_commented"), h.names())
        val report = h.reports.last()
        assertEquals("Too slow", report.properties.get("comment").asString)
        assertEquals(JsonPrimitive(2), report.properties.get("value"))
        assertEquals(6, report.properties.size())

        h.presenter.finish(InAppOutcome.Answered(InAppAnswer.Stars(2), "Too slow"))
        assertEquals(listOf("in_app_viewed", "in_app_answered", "in_app_commented"), h.names())
    }

    @Test
    fun `a comment before the answer or after the SDK stopped is not reported`() {
        val h = InAppHarness()
        h.start(listOf(asking()))
        h.controller.onEvent(cart)
        val shown = h.presenter.shown.last()
        shown.onComment!!("Early")
        assertEquals(listOf("in_app_viewed"), h.names())
        shown.onAnswer!!(InAppAnswer.Stars(2))
        h.controller.stop()
        shown.onComment!!("Late")
        assertEquals(listOf("in_app_viewed", "in_app_answered"), h.names())
    }

    @Test
    fun `an answered message reports no dismissal and no click and tells the host`() {
        val h = InAppHarness()
        h.start(listOf(asking()))
        h.controller.onEvent(cart)
        answer(h, InAppAnswer.Stars(5))
        h.presenter.shown.last().onComment!!("Great")
        h.presenter.finish(InAppOutcome.Answered(InAppAnswer.Stars(5), "Great"))

        assertEquals(listOf("in_app_viewed", "in_app_answered", "in_app_commented"), h.names())
        assertEquals(listOf(Triple<String, Any, String?>("c1", 5, "Great")), h.listener.answers)
        assertTrue(h.listener.dismissals.isEmpty())
        assertTrue(h.listener.clicks.isEmpty())
    }

    @Test
    fun `a question closed before the answer is a dismissal and is asked again`() {
        val h = InAppHarness()
        h.start(listOf(asking()))
        h.controller.onEvent(cart)
        h.presenter.finish(InAppOutcome.Dismissed("close_button"))
        assertEquals(listOf("in_app_viewed", "in_app_dismissed"), h.names())
        assertTrue(h.listener.answers.isEmpty())
        assertEquals(emptyList<String>(), h.kept()!!.state.answered)
        h.controller.onEvent(cart)
        assertEquals(listOf("c1", "c1"), h.presenter.ids)
    }

    @Test
    fun `an answered campaign is not shown again, now or after a restart`() {
        val h = InAppHarness()
        h.start(listOf(asking()))
        h.controller.onEvent(cart)
        answer(h, InAppAnswer.Stars(2))
        h.presenter.finish(InAppOutcome.Answered(InAppAnswer.Stars(2), null))
        h.controller.onEvent(cart)
        h.session = "s2"
        h.controller.onEvent(cart)
        assertEquals(listOf("c1"), h.presenter.ids)

        val relaunched = InAppHarness(h.store)
        relaunched.start(listOf(asking()))
        relaunched.controller.onEvent(cart)
        assertEquals(emptyList<String>(), relaunched.presenter.ids)
        assertTrue(relaunched.reports.isEmpty())
    }

    @Test
    fun `an answer kept only in memory still stops the campaign`() {
        val h = InAppHarness()
        h.store.refuses = true
        h.start(listOf(asking()))
        h.controller.onEvent(cart)
        answer(h, InAppAnswer.Stars(2))
        h.presenter.finish(InAppOutcome.Answered(InAppAnswer.Stars(2), null))
        h.controller.onEvent(cart)
        assertEquals(listOf("c1"), h.presenter.ids)
    }

    @Test
    fun `an answer triggers the campaign that follows it once the first has closed`() {
        fun followUp(id: String, op: String, value: String, field: String = "value") =
            campaign("""{"id":"$id","triggers":[{"type":"event","name":"in_app_answered","filters":[{"field":"$field","op":"$op","value":$value}]}]}""")
        val h = InAppHarness()
        h.start(listOf(asking(), followUp("low", "lt", "3"), followUp("high", "gt", "3"), followUp("elsewhere", "eq", "\"c9\"", "campaign_id")))
        h.controller.onEvent(cart)
        answer(h, InAppAnswer.Stars(5))
        assertEquals("the first message is still on screen", listOf("c1"), h.presenter.ids)

        h.presenter.finish(InAppOutcome.Answered(InAppAnswer.Stars(5), null))
        assertEquals(listOf("c1", "high"), h.presenter.ids)
        assertEquals(listOf("in_app_viewed", "in_app_answered", "in_app_viewed"), h.names())
    }

    @Test
    fun `the answer event carries the question for filters`() {
        val filters = """[{"field":"campaign_id","op":"eq","value":"c1"},{"field":"question_id","op":"eq","value":"q1"},
            {"field":"type","op":"eq","value":"choice"},{"field":"value","op":"eq","value":"friend"}]"""
        val h = InAppHarness()
        h.start(listOf(asking("choice"), campaign("""{"id":"c2","triggers":[{"type":"event","name":"in_app_answered","filters":$filters}]}""")))
        h.controller.onEvent(cart)
        answer(h, InAppAnswer.Option("friend"))
        h.presenter.finish(InAppOutcome.Answered(InAppAnswer.Option("friend"), null))
        assertEquals(listOf("c1", "c2"), h.presenter.ids)
    }

    @Test
    fun `an answer after the SDK stopped is not reported`() {
        val h = InAppHarness()
        h.start(listOf(asking()))
        h.controller.onEvent(cart)
        val shown = h.presenter.shown.last()
        h.controller.stop()
        shown.onAnswer!!(InAppAnswer.Stars(3))
        assertEquals(listOf("in_app_viewed"), h.names())
    }

    @Test
    fun `a test message with a question reports and records nothing`() {
        val h = InAppHarness()
        val follow = campaign("""{"id":"follow","triggers":[{"type":"event","name":"in_app_answered"}]}""")
        h.start(listOf(follow), testMessages(asking("stars", """{"review_from":4}""", """{"id":"t1"}""")))
        val shown = h.presenter.shown.single()
        assertTrue(shown.isTest)
        assertTrue(shown.onAnswer == null && shown.onComment == null)
        h.presenter.finish(InAppOutcome.Answered(InAppAnswer.Stars(5), "Great"))
        assertTrue(h.reports.isEmpty())
        assertTrue(h.listener.answers.isEmpty())
        assertEquals("no event reached the engine", listOf("t1"), h.presenter.ids)
    }

    @Test
    fun `does not show what waits while the review prompt has its turn`() {
        for ((stars, atOnce) in listOf(4 to false, 3 to true)) {
            val h = InAppHarness()
            h.start(listOf(asking("stars", """{"review_from":4}"""), campaign("""{"id":"c2","priority":0}""")))
            h.controller.onEvent(cart)
            h.controller.onEvent(cart)
            answer(h, InAppAnswer.Stars(stars))
            h.presenter.finish(InAppOutcome.Answered(InAppAnswer.Stars(stars), null))
            assertEquals("$stars stars", if (atOnce) listOf("c1", "c2") else listOf("c1"), h.presenter.ids)
            h.controller.onForeground()
            assertEquals("$stars stars", listOf("c1", "c2"), h.presenter.ids)
        }
    }

    // html hooks, IOS/InAppController.swift:324-344

    @Test
    fun `an html view is counted only once the page is shown`() {
        val h = InAppHarness()
        h.start(listOf(html()))
        h.controller.onEvent(InAppEvent(InAppEventType.EVENT, "cart_viewed", json("""{"sku":"A1"}""")))
        val presentation = h.presenter.shown.single()
        assertEquals("Ana", presentation.user.get("first_name").asString)
        assertEquals("A1", presentation.event.get("sku").asString)
        assertTrue(h.reports.isEmpty())
        presentation.onShown!!()
        presentation.onShown!!()
        assertEquals(listOf("in_app_viewed"), h.names())
        assertEquals("html", h.reports.single().properties.get("template").asString)
        assertEquals(listOf("c1"), h.listener.shown)
    }

    @Test
    fun `the page asks the controller before it is revealed`() {
        val h = InAppHarness()
        h.start(listOf(html()))
        h.controller.onEvent(cart)
        val canShow = h.presenter.shown.single().canShow!!
        assertTrue(canShow())
        h.controller.pause()
        assertFalse(canShow())
        h.controller.resume()
        h.isActive = false
        assertFalse(canShow())
    }

    @Test
    fun `the page is not revealed on an excluded screen or after the SDK stopped`() {
        val h = InAppHarness()
        h.controller.setExcludedScreens(listOf("checkout"))
        h.start(listOf(html()))
        h.controller.onEvent(cart)
        val canShow = h.presenter.shown.single().canShow!!
        assertTrue(canShow())
        h.controller.onEvent(screen("Checkout"))
        assertFalse(canShow())
        h.controller.onEvent(screen("Home"))
        assertTrue(canShow())
        h.controller.stop()
        assertFalse(canShow())
    }

    @Test
    fun `an unshown page consumes no cap, reports nothing and lets what waited show`() {
        val h = InAppHarness()
        h.start(listOf(html("""{"caps":{"session":1}}""")))
        h.controller.onEvent(cart)
        h.controller.onEvent(cart)
        h.presenter.finish(InAppOutcome.Unshown("timeout"))
        assertTrue(h.reports.isEmpty())
        assertEquals(0, h.kept()!!.state.views.size)
        assertEquals(listOf("c1", "c1"), h.presenter.ids)
    }

    @Test
    fun `track records the event and counts the click once`() {
        val h = InAppHarness()
        h.start(listOf(html()))
        h.controller.onEvent(cart)
        val presentation = h.presenter.shown.single()
        presentation.onShown!!()
        presentation.onTrack!!("chose_plan", json("""{"plan":"pro"}"""))
        presentation.onTrack!!("chose_plan", json("""{"plan":"max"}"""))
        assertEquals(listOf("chose_plan", "chose_plan"), h.tracked.map { it.first })
        assertEquals("c1", h.tracked.first().second.get("campaign_id").asString)
        assertEquals("pro", h.tracked.first().second.get("plan").asString)
        assertEquals(1, h.names().count { it == "in_app_clicked" })
        val click = h.reports.first { it.name == "in_app_clicked" }
        assertFalse(click.properties.has("button_id"))
        assertEquals("track", click.properties.get("action").asString)
        assertEquals("c1", click.properties.get("campaign_id").asString)
        assertEquals(JsonPrimitive(1), click.properties.get("revision"))

        h.presenter.finish(InAppOutcome.Clicked(null, InAppAction(InAppActionType.OPEN_URL, "https://a.example", null)))
        assertEquals(1, h.names().count { it == "in_app_clicked" })
        assertFalse(h.names().contains("in_app_dismissed"))
        assertEquals(1, h.listener.clicks.size)
    }

    @Test
    fun `set attribute goes to the SDK and is not a click`() {
        val h = InAppHarness()
        h.start(listOf(html()))
        h.controller.onEvent(cart)
        h.presenter.shown.single().onShown!!()
        h.presenter.shown.single().onAttribute!!("plan", JsonPrimitive("pro"))
        assertEquals("plan", h.attributes.single().first)
        assertEquals(JsonPrimitive("pro"), h.attributes.single().second)
        assertFalse(h.names().contains("in_app_clicked"))
    }

    @Test
    fun `a test html message logs track and set attribute and counts nothing`() {
        val h = InAppHarness()
        h.start(emptyList(), testMessages(html()))
        val presentation = h.presenter.shown.single()
        assertTrue(presentation.isTest)
        presentation.onShown?.invoke()
        presentation.onTrack!!("chose_plan", json("{}"))
        presentation.onAttribute!!("plan", JsonPrimitive("pro"))
        h.presenter.finish(InAppOutcome.Clicked(null, InAppAction(InAppActionType.OPEN_URL, "https://a.example", null)))
        assertTrue(h.tracked.isEmpty())
        assertTrue(h.attributes.isEmpty())
        assertTrue(h.reports.isEmpty())
    }
    @Test
    fun `once the SDK cannot run a message on screen reaches neither the host nor the attributes`() {
        val h = InAppHarness()
        h.start(listOf(html()))
        h.controller.onEvent(cart)
        val presentation = h.presenter.shown.single()
        presentation.onShown!!()
        h.canRun = false
        presentation.onAttribute!!("plan", JsonPrimitive("pro"))
        presentation.onTrack!!("chose_plan", json("{}"))
        h.presenter.finish(InAppOutcome.Dismissed("close_button"))
        assertTrue(h.attributes.isEmpty())
        assertTrue(h.tracked.isEmpty())
        assertTrue(h.listener.clicks.isEmpty())
        assertTrue(h.listener.dismissals.isEmpty())
        assertFalse(h.names().contains("in_app_dismissed"))
    }

    @Test
    fun `once the SDK cannot run an answer reaches neither the host nor the reports`() {
        val h = InAppHarness()
        h.start(listOf(asking()))
        h.controller.onEvent(cart)
        h.canRun = false
        answer(h, InAppAnswer.Stars(4))
        h.presenter.shown.single().onComment!!("nice")
        h.presenter.finish(InAppOutcome.Answered(InAppAnswer.Stars(4), "nice"))
        assertTrue(h.listener.answers.isEmpty())
        assertFalse(h.names().contains("in_app_answered"))
        assertFalse(h.names().contains("in_app_commented"))
    }

    @Test
    fun `a message that closes while the SDK cannot run still frees the screen`() {
        val h = InAppHarness()
        h.start(listOf(asking(), html("""{"id":"c2"}""")))
        h.controller.onEvent(cart)
        val first = h.presenter.shown.size
        h.canRun = false
        h.presenter.finish(InAppOutcome.Dismissed("close_button"))
        h.canRun = true
        h.controller.onEvent(cart)
        assertEquals(first + 1, h.presenter.shown.size)
    }
}
