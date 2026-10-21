package io.grovs.inapp

import io.grovs.inapp.InAppFixtures.campaign
import io.grovs.inapp.InAppFixtures.json
import io.grovs.inapp.model.InAppAction
import io.grovs.inapp.model.InAppActionType
import io.grovs.inapp.model.InAppEvent
import io.grovs.inapp.model.InAppEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppControllerTest {

    private val cart = InAppEvent(InAppEventType.EVENT, "cart_viewed")

    @Test
    fun `syncs on start, shows a message and reports the view under its id`() {
        val h = InAppHarness()
        h.start(listOf(campaign()))
        val sent = h.syncs.single()
        assertEquals(1, sent.get("format").asInt)
        assertEquals("4.0", sent.get("sdk_version").asString)
        assertTrue(sent.get("campaigns_changed_at").isJsonNull)

        h.controller.onEvent(cart)
        assertEquals(listOf("c1"), h.presenter.ids)
        assertEquals("Hello", h.presenter.shown.single().rendered.title)
        assertEquals(listOf("in_app_viewed"), h.names())
        val view = h.reports.single()
        assertEquals("c1", view.properties.get("campaign_id").asString)
        assertEquals(1, view.properties.get("revision").asInt)
        assertEquals("interstitial", view.properties.get("template").asString)
        assertEquals(listOf(view.id), h.kept()!!.state.views.map { it.id })
        assertEquals(listOf("c1"), h.listener.shown)
    }

    @Test
    fun `with no campaign an event does not read the device`() {
        val h = InAppHarness()
        h.start(emptyList())
        val reads = h.deviceReads
        repeat(3) { h.controller.onEvent(cart) }
        assertEquals(reads, h.deviceReads)
    }

    @Test
    fun `an event that changes nothing writes no state`() {
        val h = InAppHarness()
        h.start(listOf(campaign()))
        val writes = h.store.written.size
        repeat(3) { h.controller.onEvent(InAppEvent(InAppEventType.EVENT, "unrelated")) }
        assertEquals(writes, h.store.written.size)
    }

    @Test
    fun `reports a click and hands the payload to the host`() {
        val h = InAppHarness()
        h.start(listOf(campaign("""{"content":{"buttons":[{"id":"b1","text":"Go","action":{"type":"key_value","payload":{"screen":"offers"}}}]}}""")))
        h.controller.onEvent(cart)
        h.presenter.finish(InAppOutcome.Clicked("b1", InAppAction(InAppActionType.KEY_VALUE, null, json("""{"screen":"offers"}"""))))

        val click = h.reports.last()
        assertEquals("in_app_clicked", click.name)
        assertEquals("b1", click.properties.get("button_id").asString)
        assertEquals("key_value", click.properties.get("action").asString)
        assertEquals(Triple("c1", "b1", mapOf<String, Any?>("screen" to "offers")), h.listener.clicks.single())
    }

    @Test
    fun `a tap on the message names no button`() {
        val h = InAppHarness()
        h.start(listOf(campaign()))
        h.controller.onEvent(cart)
        h.presenter.finish(InAppOutcome.Clicked(null, InAppAction(InAppActionType.CLOSE, null, null)))
        assertEquals("in_app_clicked", h.reports.last().name)
        assertFalse(h.reports.last().properties.has("button_id"))
    }

    @Test
    fun `reports a dismissal with its reason`() {
        val h = InAppHarness()
        h.start(listOf(campaign()))
        h.controller.onEvent(cart)
        h.presenter.finish(InAppOutcome.Dismissed("backdrop"))
        assertEquals("in_app_dismissed", h.reports.last().name)
        assertEquals("backdrop", h.reports.last().properties.get("reason").asString)
        assertEquals(listOf("c1" to "backdrop"), h.listener.dismissals)
    }

    @Test
    fun `a vetoed message gains no view and reports nothing`() {
        val h = InAppHarness()
        h.start(listOf(campaign("""{"caps":{"lifetime":1}}""")))
        h.listener.veto = { false }
        h.controller.onEvent(cart)
        assertEquals(emptyList<String>(), h.presenter.ids)
        assertTrue(h.reports.isEmpty())

        h.listener.veto = null
        h.controller.onEvent(cart)
        assertEquals(listOf("c1"), h.presenter.ids)
    }

    @Test
    fun `a host that stops the SDK while asked sees no message`() {
        val h = InAppHarness()
        h.start(listOf(campaign()))
        h.listener.veto = { h.canRun = false; true }
        h.controller.onEvent(cart)
        assertEquals(emptyList<String>(), h.presenter.ids)
        assertTrue(h.reports.isEmpty())
    }

    @Test
    fun `a message that could not be drawn gains no view and reaches the error callback`() {
        val h = InAppHarness()
        h.start(listOf(campaign("""{"caps":{"lifetime":1}}""")))
        h.presenter.refuses = true
        h.controller.onEvent(cart)
        assertTrue(h.reports.isEmpty())
        assertTrue(h.listener.shown.isEmpty())
        assertEquals(1, h.failures.size)

        h.presenter.refuses = false
        h.controller.onEvent(cart)
        assertEquals(listOf("c1"), h.presenter.ids)
        assertEquals(listOf("in_app_viewed"), h.names())
    }

    @Test
    fun `does nothing while the SDK may not run`() {
        val h = InAppHarness()
        h.canRun = false
        h.start(listOf(campaign()))
        h.controller.onEvent(cart)
        assertTrue(h.syncs.isEmpty())
        assertEquals(emptyList<String>(), h.presenter.ids)

        h.canRun = true
        h.controller.start()
        h.canRun = false
        h.controller.onEvent(cart)
        h.controller.onBatchStamps("c2", null)
        assertEquals(emptyList<String>(), h.presenter.ids)
        assertTrue(h.timers.isEmpty())
    }

    @Test
    fun `stop closes what is showing without reporting it`() {
        val h = InAppHarness()
        h.start(listOf(campaign()))
        h.controller.onEvent(cart)
        h.controller.stop()
        assertEquals(1, h.presenter.dismissals)
        assertEquals(listOf("in_app_viewed"), h.names())
        assertTrue(h.listener.dismissals.isEmpty())
        assertFalse(h.controller.isRunning)
    }

    @Test
    fun `a late sync answer after stop is dropped`() {
        val h = InAppHarness()
        h.holdSync = true
        h.respond(listOf(campaign()))
        h.controller.start()
        val late = h.heldSync!!
        h.controller.stop()
        late()
        assertNull(InAppSynced.load(h.store))

        h.holdSync = false
        h.respond(listOf(campaign("""{"id":"c2"}""")))
        h.controller.start()
        h.controller.onEvent(cart)
        assertEquals(listOf("c2"), h.presenter.ids)
    }

    @Test
    fun `keeps the stored campaigns when a sync fails`() {
        val first = InAppHarness()
        first.start(listOf(campaign()))
        val h = InAppHarness(first.store)
        h.controller.start()
        h.controller.onEvent(cart)
        assertEquals(listOf("c1"), h.presenter.ids)
    }

    @Test
    fun `starts empty from a store it cannot read`() {
        for (raw in listOf("not json", "[]", "{\"campaigns\":5}", "{\"state\":{\"views\":5}}", "{\"state\":5}")) {
            val store = FakeInAppStore()
            InAppRecord.values().forEach { store.records[it] = raw }
            val h = InAppHarness(store)
            h.start(listOf(campaign()))
            h.controller.onEvent(cart)
            assertEquals(raw, listOf("c1"), h.presenter.ids)
        }
    }

    @Test
    fun `showing a message rewrites the small record, not the campaigns`() {
        val h = InAppHarness()
        h.start(listOf(campaign("""{"caps":{"session":null}}""")))
        h.store.written.clear()
        h.controller.onEvent(cart)
        h.presenter.finish(InAppOutcome.Dismissed("close_button"))
        h.controller.onEvent(cart)
        assertTrue(h.store.written.isNotEmpty())
        assertFalse(h.store.written.contains(InAppRecord.CAMPAIGNS))
    }

    @Test
    fun `carries on in memory when the store refuses writes`() {
        val h = InAppHarness()
        h.store.refuses = true
        h.start(listOf(campaign("""{"caps":{"session":1}}""")))
        h.controller.onEvent(cart)
        h.presenter.finish(InAppOutcome.Dismissed("close_button"))
        h.controller.onEvent(cart)
        assertEquals(listOf("c1"), h.presenter.ids)
    }

    @Test
    fun `keeps a lifetime cap across launches`() {
        val first = InAppHarness()
        first.start(listOf(campaign("""{"caps":{"lifetime":1}}""")))
        first.controller.onEvent(cart)
        assertEquals(listOf("c1"), first.presenter.ids)

        val h = InAppHarness(first.store)
        h.start(listOf(campaign("""{"caps":{"lifetime":1}}""")))
        h.controller.onEvent(cart)
        assertEquals(emptyList<String>(), h.presenter.ids)
    }

    @Test
    fun `keeps a session cap across a sync and a launch and resets it with the session`() {
        val h = InAppHarness()
        h.start(listOf(campaign("""{"caps":{"session":1}}""")))
        h.controller.onEvent(cart)
        h.presenter.finish(InAppOutcome.Dismissed("close_button"))
        h.controller.onBatchStamps("c9", null)
        h.timers.first().work()
        h.controller.onEvent(cart)
        assertEquals(listOf("c1"), h.presenter.ids)

        val relaunched = InAppHarness(h.store)
        relaunched.start(listOf(campaign("""{"caps":{"session":1}}""")))
        relaunched.controller.onEvent(cart)
        assertEquals(emptyList<String>(), relaunched.presenter.ids)

        relaunched.session = "s2"
        relaunched.controller.onEvent(cart)
        assertEquals(listOf("c1"), relaunched.presenter.ids)
    }

    @Test
    fun `personalization uses the user as it is when the message is decided`() {
        val h = InAppHarness()
        h.start(listOf(campaign("""{"caps":{"session":null},"content":{"title":"Hi {{ user.first_name | default: \"there\" }}"}}""")))
        h.controller.onEvent(cart)
        assertEquals("Hi Ana", h.presenter.shown.last().rendered.title)
        h.presenter.finish(InAppOutcome.Dismissed("close_button"))
        h.user = json("""{"first_name":"Bob"}""")
        h.controller.onEvent(cart)
        assertEquals("Hi Bob", h.presenter.shown.last().rendered.title)
        assertEquals("Bob", h.presenter.shown.last().user.get("first_name").asString)
    }

    @Test
    fun `an ordinary message is not a test and can be answered`() {
        val h = InAppHarness()
        h.start(listOf(campaign()))
        h.controller.onEvent(cart)
        assertFalse(h.presenter.shown.last().isTest)
        assertTrue(h.presenter.shown.last().onAnswer != null)
        assertTrue(h.presenter.shown.last().onComment != null)
    }

    @Test
    fun `debug logging explains every campaign's skip reason`() {
        val h = InAppHarness()
        h.start(listOf(campaign(), campaign("""{"id":"c2","triggers":[{"type":"event","name":"other"}]}""")))
        h.controller.onEvent(cart)
        assertTrue(h.debugLines.isEmpty())
        h.presenter.finish(InAppOutcome.Dismissed("close_button"))
        h.settings.debug = true
        h.controller.onEvent(cart)
        assertTrue(h.debugLines.single(), h.debugLines.single().contains("c2: no_trigger"))
    }

    @Test
    fun `a completion that arrives after stop and a fresh start reports nothing`() {
        val h = InAppHarness()
        h.presenter.ignoresDismiss = true
        h.start(listOf(campaign()))
        h.controller.onEvent(cart)
        val reported = h.reports.size
        h.controller.stop()
        h.controller.start()
        h.presenter.finish(InAppOutcome.Dismissed("close_button"))
        assertEquals(reported, h.reports.size)
        assertTrue(h.listener.dismissals.isEmpty())
    }
}
