package io.grovs.inapp

import io.grovs.inapp.InAppFixtures.campaign
import io.grovs.inapp.model.InAppAction
import io.grovs.inapp.model.InAppActionType
import io.grovs.inapp.model.InAppEvent
import io.grovs.inapp.model.InAppEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppControllerWaitingTest {

    private val cart = InAppEvent(InAppEventType.EVENT, "cart_viewed")
    private fun event(name: String) = InAppEvent(InAppEventType.EVENT, name)
    private fun screen(name: String) = InAppEvent(InAppEventType.SCREEN_VIEW, name)
    private val appOpen = InAppEvent(InAppEventType.APP_OPEN)

    // IOS/InAppController.swift:263-274 delay(_:only:priority:seconds:)

    @Test
    fun `a delayed message waits and gains its view only when shown`() {
        val h = InAppHarness()
        h.start(listOf(campaign("""{"delay_seconds":5}""")))
        h.controller.onEvent(cart)
        assertEquals(emptyList<String>(), h.presenter.ids)
        assertTrue(h.reports.isEmpty())

        h.timer(5_000)!!.work()
        assertEquals(listOf("c1"), h.presenter.ids)
        assertEquals(listOf("in_app_viewed"), h.names())
    }

    @Test
    fun `a delayed message that no longer passes is dropped and makes way for no other`() {
        val h = InAppHarness()
        val end = h.iso(h.clock + 3_000)
        h.start(listOf(
            campaign("""{"id":"late","priority":9,"delay_seconds":5,"schedule":{"end_at":"$end"}}"""),
            campaign("""{"id":"plain"}"""),
        ))
        h.controller.onEvent(cart)
        h.clock += 5_000
        h.timer(5_000)!!.work()
        assertEquals(emptyList<String>(), h.presenter.ids)
    }

    @Test
    fun `holds one delayed message and keeps the higher priority`() {
        val h = InAppHarness()
        h.start(listOf(
            campaign("""{"id":"low","priority":2,"delay_seconds":5,"triggers":[{"type":"event","name":"a"}]}"""),
            campaign("""{"id":"high","priority":9,"delay_seconds":3,"triggers":[{"type":"event","name":"b"}]}"""),
            campaign("""{"id":"lower","priority":1,"delay_seconds":1,"triggers":[{"type":"event","name":"c"}]}"""),
        ))
        listOf("a", "b", "c").forEach { h.controller.onEvent(event(it)) }
        assertNull("the lower one was given up", h.timer(5_000))
        assertNull(h.timer(1_000))
        h.timer(3_000)!!.work()
        assertEquals(listOf("high"), h.presenter.ids)
    }

    @Test
    fun `a delay that ends while another message is shown takes the waiting place`() {
        val h = InAppHarness()
        h.start(listOf(
            campaign("""{"id":"late","delay_seconds":5,"triggers":[{"type":"event","name":"a"}]}"""),
            campaign("""{"id":"now","triggers":[{"type":"event","name":"b"}]}"""),
        ))
        h.controller.onEvent(event("a"))
        h.controller.onEvent(event("b"))
        h.timer(5_000)!!.work()
        assertEquals(listOf("now"), h.presenter.ids)
        h.presenter.finish(InAppOutcome.Dismissed("close_button"))
        assertEquals(listOf("now", "late"), h.presenter.ids)
    }

    @Test
    fun `stopping drops a delayed message`() {
        val h = InAppHarness()
        h.start(listOf(campaign("""{"delay_seconds":5}""")))
        h.controller.onEvent(cart)
        val timer = h.timer(5_000)!!
        h.controller.stop()
        assertTrue(timer.cancelled)
        timer.work()
        assertEquals(emptyList<String>(), h.presenter.ids)
    }

    @Test
    fun `going to the background drops a delayed message`() {
        val h = InAppHarness()
        h.start(listOf(campaign("""{"delay_seconds":5}""")))
        h.controller.onEvent(cart)
        val timer = h.timer(5_000)!!
        h.controller.onBackground()
        assertTrue(timer.cancelled)
        h.controller.onForeground()
        assertEquals(emptyList<String>(), h.presenter.ids)
    }

    @Test
    fun `a waiting message that became a delayed campaign waits out its delay`() {
        val h = InAppHarness()
        h.start(listOf(campaign()))
        h.controller.pause()
        h.controller.onEvent(cart)
        h.respond(listOf(campaign("""{"id":"c2","delay_seconds":60}""")), InAppFixtures.json("""{"campaigns_changed_at":"c2"}"""))
        h.controller.onForeground()
        h.controller.resume()
        assertEquals(emptyList<String>(), h.presenter.ids)
        h.timer(60_000)!!.work()
        assertEquals(listOf("c2"), h.presenter.ids)
    }

    // IOS/InAppController.swift:241-260 handle(_:) and 276-297 showHeld()

    @Test
    fun `holds one message and keeps the higher priority`() {
        val h = InAppHarness()
        h.start(listOf(
            campaign("""{"id":"first","triggers":[{"type":"event","name":"a"}]}"""),
            campaign("""{"id":"low","priority":2,"triggers":[{"type":"event","name":"b"}]}"""),
            campaign("""{"id":"high","priority":9,"triggers":[{"type":"event","name":"c"}]}"""),
            campaign("""{"id":"lower","priority":1,"triggers":[{"type":"event","name":"d"}]}"""),
        ))
        listOf("a", "b", "c", "d").forEach { h.controller.onEvent(event(it)) }
        assertEquals(listOf("first"), h.presenter.ids)
        h.presenter.finish(InAppOutcome.Dismissed("close_button"))
        assertEquals(listOf("first", "high"), h.presenter.ids)
        h.presenter.finish(InAppOutcome.Dismissed("close_button"))
        assertEquals(listOf("first", "high"), h.presenter.ids)
    }

    @Test
    fun `a waiting message is decided again and dropped when it no longer passes`() {
        val h = InAppHarness()
        val end = h.iso(h.clock + 60_000)
        h.start(listOf(
            campaign("""{"id":"first","priority":9,"triggers":[{"type":"event","name":"a"}]}"""),
            campaign("""{"id":"brief","triggers":[{"type":"event","name":"b"}],"schedule":{"end_at":"$end"}}"""),
        ))
        h.controller.onEvent(event("a"))
        h.controller.onEvent(event("b"))
        h.clock += 300_000
        h.presenter.finish(InAppOutcome.Dismissed("close_button"))
        assertEquals(listOf("first"), h.presenter.ids)
        assertEquals(1, h.names().count { it == "in_app_viewed" })
    }

    @Test
    fun `does not show what waits while a link takes the person away`() {
        val h = InAppHarness()
        h.start(listOf(campaign("""{"caps":{"session":null}}""")))
        h.controller.onEvent(cart)
        h.controller.onEvent(cart)
        h.presenter.finish(InAppOutcome.Clicked("b1", InAppAction(InAppActionType.OPEN_URL, "https://a.example", null)))
        assertEquals(listOf("c1"), h.presenter.ids)
        h.controller.onForeground()
        assertEquals(listOf("c1", "c1"), h.presenter.ids)
    }

    // IOS/InAppController.swift:170-193 pause, resume, discard, excluded; 472-477 blocked

    @Test
    fun `holds messages while paused, on an excluded screen and in the background`() {
        val h = InAppHarness()
        h.start(listOf(campaign("""{"caps":{"session":null}}""")))

        h.controller.pause()
        h.controller.onEvent(cart)
        assertEquals(emptyList<String>(), h.presenter.ids)
        h.controller.resume()
        assertEquals(listOf("c1"), h.presenter.ids)
        h.presenter.finish(InAppOutcome.Dismissed("close_button"))

        h.controller.setExcludedScreens(listOf("Checkout"))
        h.controller.onEvent(screen("checkout"))
        h.controller.onEvent(cart)
        assertEquals(1, h.presenter.ids.size)
        h.controller.onEvent(screen("Home"))
        assertEquals(2, h.presenter.ids.size)
        h.presenter.finish(InAppOutcome.Dismissed("close_button"))

        h.isActive = false
        h.controller.onEvent(cart)
        assertEquals(2, h.presenter.ids.size)
        h.isActive = true
        h.controller.onForeground()
        assertEquals(3, h.presenter.ids.size)
    }

    @Test
    fun `discard drops what is waiting and can close what is showing`() {
        val h = InAppHarness()
        h.start(listOf(
            campaign("""{"id":"first","triggers":[{"type":"event","name":"a"}]}"""),
            campaign("""{"id":"second","triggers":[{"type":"event","name":"b"}]}"""),
        ))
        h.controller.onEvent(event("a"))
        h.controller.onEvent(event("b"))
        h.controller.discard(dismissCurrent = true)
        h.controller.resume()
        assertEquals(listOf("first"), h.presenter.ids)
        assertEquals(listOf("in_app_viewed", "in_app_dismissed"), h.names())
        assertEquals("app", h.reports.last().properties.get("reason").asString)
    }

    @Test
    fun `pause, resume and excluded screens live in the settings`() {
        val h = InAppHarness()
        h.controller.pause()
        assertTrue(h.settings.paused)
        h.controller.setExcludedScreens(listOf("Checkout", "PAY"))
        assertEquals(setOf("checkout", "pay"), h.settings.excluded)
        h.controller.resume()
        assertEquals(false, h.settings.paused)
    }

    // IOS/InAppController.swift:479-496 hold(_:)

    @Test
    fun `app_open waits for the sync in flight`() {
        val h = InAppHarness()
        h.holdSync = true
        h.respond(listOf(campaign("""{"triggers":[{"type":"app_open"}]}""")))
        h.controller.start()
        h.controller.onEvent(appOpen)
        assertEquals(emptyList<String>(), h.presenter.ids)
        assertEquals(listOf(InAppController.OPEN_WAIT_MS), h.timers.map { it.delay })

        h.heldSync!!()
        assertEquals(listOf("c1"), h.presenter.ids)
    }

    @Test
    fun `app_open stops waiting after two seconds and uses what is stored`() {
        val first = InAppHarness()
        first.start(listOf(campaign("""{"triggers":[{"type":"app_open"}]}""")))

        val h = InAppHarness(first.store)
        h.holdSync = true
        h.controller.start()
        h.controller.onAppOpen()
        assertEquals(emptyList<String>(), h.presenter.ids)
        h.timers.first().work()
        assertEquals(listOf("c1"), h.presenter.ids)
        h.heldSync!!()
        assertEquals(listOf("c1"), h.presenter.ids)
    }

    @Test
    fun `discard drops an opening that was waiting for the sync`() {
        val h = InAppHarness()
        h.holdSync = true
        h.start(listOf(campaign("""{"triggers":[{"type":"app_open"}]}""")))
        h.controller.onEvent(appOpen)
        h.controller.discard(dismissCurrent = false)
        h.heldSync!!()
        h.controller.resume()
        assertEquals(emptyList<String>(), h.presenter.ids)
    }

    @Test
    fun `keeps an opening message off an excluded screen tracked before in-app started`() {
        val h = InAppHarness()
        h.controller.setExcludedScreens(listOf("Checkout"))
        h.controller.onEvent(screen("checkout"))
        h.start(listOf(campaign("""{"triggers":[{"type":"app_open"}]}""")))
        h.controller.onEvent(appOpen)
        assertEquals(emptyList<String>(), h.presenter.ids)

        h.controller.onEvent(screen("home"))
        assertEquals(listOf("c1"), h.presenter.ids)
    }

    @Test
    fun `decides the screen the app opened on once in-app has started`() {
        val h = InAppHarness()
        h.controller.onEvent(screen("Pricing"))
        h.start(listOf(campaign("""{"triggers":[{"type":"screen_view","name":"pricing"}]}""")))
        assertEquals(listOf("c1"), h.presenter.ids)
    }
}
