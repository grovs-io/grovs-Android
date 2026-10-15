package io.grovs.inapp

import com.google.gson.JsonObject
import io.grovs.inapp.InAppFixtures.campaign
import io.grovs.inapp.InAppFixtures.json
import io.grovs.inapp.model.InAppEvent
import io.grovs.inapp.model.InAppEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InAppControllerSyncTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val cart = InAppEvent(InAppEventType.EVENT, "cart_viewed")
    private var download: ((ByteArray?) -> Unit)? = null

    /** A first launch: the opening is held for the sync, whose campaign's picture is still downloading. */
    private fun openingWithoutPicture(vararg others: JsonObject): InAppHarness {
        val images = InAppImages(temp.root, loader = { _, done -> download = done }, decodes = { true })
        val h = InAppHarness(imageCache = images)
        h.holdSync = true
        h.start(listOf(campaign("""{"triggers":[{"type":"app_open"}],"content":{"image":{"portrait":"https://cdn.example/welcome.png"}}}""")) + others)
        h.controller.onAppOpen()
        h.heldSync!!.invoke()
        return h
    }

    private val storeButton = """{"content":{"buttons":[{"id":"b1","text":"Update","action":{"type":"open_store"}}]}}"""

    // IOS/InAppController.swift:208-220 onBatchResponse

    @Test
    fun `syncs again once after a random delay when the batch stamps change`() {
        val h = InAppHarness()
        h.start(listOf(campaign()))
        assertEquals(1, h.syncs.size)

        h.controller.onBatchStamps("c1", "u1")
        h.controller.onBatchStamps(null, null)
        assertTrue(h.timers.isEmpty())

        h.controller.onBatchStamps("c2", "u1")
        h.controller.onBatchStamps("c2", "u1")
        assertEquals(listOf(5_000L), h.timers.map { it.delay })

        h.timers.first().work()
        assertEquals(2, h.syncs.size)
        assertEquals("c1", h.syncs.last().get("campaigns_changed_at").asString)
    }

    @Test
    fun `a stamp change seen during a sync syncs once more afterwards`() {
        val h = InAppHarness()
        h.start(listOf(campaign()))
        h.holdSync = true
        h.controller.onForeground()
        assertEquals(2, h.syncs.size)
        h.controller.onBatchStamps("c2", null)
        h.timers.first().work()
        assertEquals(2, h.syncs.size)
        h.holdSync = false
        h.heldSync!!()
        assertEquals(3, h.syncs.size)
    }

    // IOS/InAppController.swift:229, 529-537

    @Test
    fun `syncs again on an event after fifteen minutes of use`() {
        val h = InAppHarness()
        h.start(listOf(campaign()))
        h.clock += 16 * 60_000
        h.controller.onEvent(InAppEvent(InAppEventType.EVENT, "other"))
        assertEquals(2, h.syncs.size)
    }

    @Test
    fun `syncs every fifteen minutes while in use with no events`() {
        val h = InAppHarness()
        h.start(listOf(campaign()))
        h.every.last { it.delay == InAppController.SYNC_EVERY_MS && !it.cancelled }.work()
        assertEquals(2, h.syncs.size)

        h.isActive = false
        h.every.last { it.delay == InAppController.SYNC_EVERY_MS && !it.cancelled }.work()
        assertEquals(2, h.syncs.size)
    }

    // IOS/InAppController.swift:539-571 apply

    @Test
    fun `uses the server clock so a wrong device clock cannot show an ended campaign`() {
        val h = InAppHarness()
        val real = h.clock
        h.clock = real - 7 * 86_400_000L
        h.respond(listOf(campaign("""{"schedule":{"end_at":"${h.iso(real - 60_000)}"}}""")), json("""{"server_time":"${h.iso(real)}"}"""))
        h.controller.start()
        h.controller.onEvent(cart)
        assertEquals(emptyList<String>(), h.presenter.ids)
    }

    @Test
    fun `shows nothing once the last sync is more than 48 hours old`() {
        val h = InAppHarness()
        h.start(listOf(campaign()))
        h.response = false to null
        h.clock += 48 * 3_600_000L + 1
        h.controller.onEvent(cart)
        assertEquals(emptyList<String>(), h.presenter.ids)
    }

    @Test
    fun `an unchanged answer keeps the campaigns and restarts the 48 hour clock`() {
        val h = InAppHarness()
        h.start(listOf(campaign()))
        h.clock += 47 * 3_600_000L
        h.unchanged()
        h.controller.onForeground()
        h.clock += 2 * 3_600_000L
        h.response = false to null
        h.controller.onEvent(cart)
        assertEquals(listOf("c1"), h.presenter.ids)
    }

    @Test
    fun `an off answer shows nothing`() {
        val h = InAppHarness()
        h.respond(emptyList(), json("""{"campaigns_changed_at":"off","user_changed_at":"off","store_url":null,"first_seen_at":null}"""))
        h.controller.start()
        h.controller.onEvent(cart)
        assertEquals(emptyList<String>(), h.presenter.ids)
        h.controller.onBatchStamps("off", "off")
        assertTrue(h.timers.isEmpty())
    }

    @Test
    fun `a store button needs the https address the sync gives`() {
        val without = InAppHarness()
        without.start(listOf(campaign(storeButton)))
        without.controller.onEvent(cart)
        assertEquals(emptyList<String>(), without.presenter.ids)
        assertNull(without.controller.storeUrl)

        val with = InAppHarness()
        with.start(listOf(campaign(storeButton)), json("""{"store_url":"https://play.google.com/store/apps/details?id=a"}"""))
        with.controller.onEvent(cart)
        assertEquals(listOf("c1"), with.presenter.ids)
        assertEquals("https://play.google.com/store/apps/details?id=a", with.presenter.shown.single().storeUrl)

        for (odd in listOf("javascript:alert(1)", "http://play.google.com/x", "https://", "not a url")) {
            val h = InAppHarness()
            h.start(listOf(campaign(storeButton)), json("""{"store_url":"$odd"}"""))
            assertNull(odd, h.controller.storeUrl)
        }
    }

    @Test
    fun `a store address that arrives with an unchanged answer makes the campaign showable`() {
        val h = InAppHarness()
        h.start(listOf(campaign(storeButton)))
        h.controller.onEvent(cart)
        assertEquals(emptyList<String>(), h.presenter.ids)

        h.unchanged("""{"store_url":"https://play.google.com/store/apps/details?id=a"}""")
        h.controller.onForeground()
        h.controller.onEvent(cart)
        assertEquals(listOf("c1"), h.presenter.ids)
    }

    @Test
    fun `a store address that arrives later starts counting events for its campaign`() {
        val counting = campaign("""{"content":{"buttons":[{"id":"b1","text":"Update","action":{"type":"open_store"}}]},"conditions":[{"type":"event_count","name":"cart_viewed","op":"gt","value":1}]}""")
        val h = InAppHarness()
        h.start(listOf(counting))
        h.unchanged("""{"store_url":"https://play.google.com/store/apps/details?id=a"}""")
        h.controller.onForeground()

        h.controller.onEvent(cart)
        assertEquals(emptyList<String>(), h.presenter.ids)
        h.controller.onEvent(cart)
        assertEquals(listOf("c1"), h.presenter.ids)
    }

    // IOS/InAppController.swift:588-597, 630-638

    @Test
    fun `forgets the campaigns after fourteen days without a sync and keeps the views`() {
        val store = FakeInAppStore()
        val first = InAppHarness(store)
        first.start(listOf(campaign()))
        first.controller.onEvent(cart)
        first.controller.stop()

        val later = InAppHarness(store)
        later.clock = first.clock + 15 * 86_400_000L
        later.response = false to null
        later.controller.start()
        assertEquals(0, InAppSynced.load(store)!!.campaigns.size)
        assertEquals(1, InAppKept.load(store)!!.state.views.size)
    }

    @Test
    fun `keeps the last five thousand views`() {
        val store = FakeInAppStore()
        InAppKept(state = InAppEngineState(views = (0 until 5_003).map { InAppView("v$it", "old", 1L, true) })).save(store)
        val h = InAppHarness(store)
        h.start(listOf(campaign()))
        val views = InAppKept.load(store)!!.state.views
        assertEquals(InAppController.VIEWS_KEPT, views.size)
        assertEquals("v3", views.first().id)
    }

    // IOS/InAppController.swift:606-619 notReady, 143 and 567 preload

    @Test
    fun `does not show a campaign until its images are on the device`() {
        var serve = false
        val images = InAppImages(temp.root, loader = { _, done -> done(if (serve) byteArrayOf(1) else null) }, decodes = { true })
        val h = InAppHarness(imageCache = images)
        h.start(listOf(campaign("""{"content":{"image":{"portrait":"https://cdn.example/a.png","landscape":null}}}""")))
        h.controller.onEvent(cart)
        assertEquals(emptyList<String>(), h.presenter.ids)

        serve = true
        h.controller.onBatchStamps("c2", null)
        h.timers.first().work()
        h.controller.onEvent(cart)
        assertEquals(listOf("c1"), h.presenter.ids)
        assertTrue(images.file("https://cdn.example/a.png") != null)
    }

    @Test
    fun `an opening shows once its first picture arrives`() {
        val h = openingWithoutPicture()
        assertEquals(emptyList<String>(), h.presenter.ids)

        download!!(byteArrayOf(1))
        assertEquals(listOf("c1"), h.presenter.ids)
        assertEquals(1, h.reports.count { it.name == "in_app_viewed" })
    }

    @Test
    fun `an opening waits for its pictures only a short while`() {
        val h = openingWithoutPicture()
        h.clock += InAppController.IMAGES_WAIT_MS + 1
        download!!(byteArrayOf(1))
        assertEquals(emptyList<String>(), h.presenter.ids)
    }

    @Test
    fun `a picture arriving after a stop shows nothing`() {
        val h = openingWithoutPicture()
        h.controller.stop()
        h.controller.start()
        download!!(byteArrayOf(1))
        assertEquals(emptyList<String>(), h.presenter.ids)
    }

    @Test
    fun `a picture arriving after a discard shows nothing`() {
        val h = openingWithoutPicture()
        h.controller.discard(dismissCurrent = false)
        h.controller.resume()
        download!!(byteArrayOf(1))
        assertEquals(emptyList<String>(), h.presenter.ids)
    }

    @Test
    fun `an opening whose picture arrives while another message shows waits its turn`() {
        val h = openingWithoutPicture(campaign("""{"id":"other","triggers":[{"type":"event","name":"cart_viewed"}]}"""))
        h.controller.onEvent(cart)
        assertEquals(listOf("other"), h.presenter.ids)

        download!!(byteArrayOf(1))
        assertEquals(listOf("other"), h.presenter.ids)
        h.presenter.finish(InAppOutcome.Dismissed("close_button"))
        assertEquals(listOf("other", "c1"), h.presenter.ids)
    }

    @Test
    fun `a picture does not bring back an ordinary event that missed it`() {
        val images = InAppImages(temp.root, loader = { _, done -> download = done }, decodes = { true })
        val h = InAppHarness(imageCache = images)
        h.start(listOf(campaign("""{"content":{"image":{"portrait":"https://cdn.example/a.png"}}}""")))
        h.controller.onEvent(cart)

        download!!(byteArrayOf(1))
        assertEquals(emptyList<String>(), h.presenter.ids)
    }

    @Test
    fun `an html campaign is not held back by a leftover unreachable image`() {
        val asked = mutableListOf<String>()
        val images = InAppImages(temp.root, loader = { url, done -> asked.add(url); done(null) }, decodes = { true })
        val h = InAppHarness(imageCache = images)
        h.start(listOf(campaign("""{"template":"html","content":{"html":"<p>Hi</p>","layout":"modal","image":{"portrait":"https://cdn.example/x.png","landscape":null}}}""")))
        h.controller.onEvent(cart)
        assertEquals(listOf("c1"), h.presenter.ids)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `tries a missing picture again after an unchanged answer`() {
        var asked = 0
        val images = InAppImages(temp.root, loader = { _, done -> asked++; done(null) }, decodes = { true })
        val h = InAppHarness(imageCache = images)
        h.start(listOf(campaign("""{"content":{"image":{"portrait":"https://cdn.example.com/a.png"}}}""")))
        assertEquals(1, asked)
        h.unchanged()
        h.controller.onForeground()
        assertEquals(2, asked)
    }
}
