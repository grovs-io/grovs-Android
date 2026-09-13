package io.grovs.handlers

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.google.gson.Gson
import io.grovs.FakeClipboard
import io.grovs.FakeLocalCache
import io.grovs.TestFixtures
import io.grovs.model.BatchEventsResponse
import io.grovs.model.DeeplinkDetails
import io.grovs.model.Event
import io.grovs.model.EventType
import io.grovs.model.events.PaymentEvent
import io.grovs.model.events.PaymentEventType
import io.grovs.service.IGrovsService
import io.grovs.storage.CustomEventsStorage
import io.grovs.storage.EventsStorage
import io.grovs.utils.IAppDetailsHelper
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSResult
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/** Exercises resolution through real event managers and SharedPreferences storage. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LinkAttributionTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val clipboardUrl = "https://demo.sqd.link/copied?gd=123"
    private val directUrl = "https://demo.sqd.link/direct"
    private val empty = DeeplinkDetails(null, null, null)

    private inner class Rig(
        freshInstall: Boolean,
        // Persistent state survives a process restart; pass the previous rig's cache to simulate one.
        val cache: FakeLocalCache = FakeLocalCache(numberOfOpens = if (freshInstall) 0 else 2),
    ) {
        // Keep deadline time explicit while SharedPreferences work uses real IO threads.
        val deadlineClock = TestScope()
        val service = mockk<IGrovsService>(relaxed = true)
        val context = GrovsContext().also { it.markAuthenticated("device", it.consent.currentConfiguration) }
        val clipboard = FakeClipboard(text = clipboardUrl)
        val storage = EventsStorage(app)
        val customStorage = CustomEventsStorage(app)
        val sent = mutableListOf<Pair<EventType, String?>>()
        val sentPurchases = mutableListOf<String?>()
        val sentCustom = mutableListOf<String?>()
        val events = EventsManager(app, context, "test", service, storage, cache).also {
            // The old first-batch delay has expired. Only the resolution hold can protect events.
            it.firstRequestTime = InstantCompat.now().minusMillis(20_000)
        }
        val custom = CustomEventsManager(app, context, service, customStorage, startFlushTimer = false)
        val manager: GrovsManager

        init {
            val helper = mockk<IAppDetailsHelper>(relaxed = true)
            coEvery { helper.toAppDetails() } answers { TestFixtures.createAppDetails() }
            coEvery { service.payloadFor(any()) } returns LSResult.Success(empty)
            coEvery { service.payloadWithLinkFor(any()) } returns LSResult.Success(DeeplinkDetails(directUrl, null, null))
            coEvery { service.clipboardStatus() } returns LSResult.Success(true)
            coEvery { service.addEvents(any()) } answers {
                val events = firstArg<List<Event>>()
                events.forEach { sent.add(it.event to it.link) }
                LSResult.Success(BatchEventsResponse(accepted = events.size, rejected = 0))
            }
            coEvery { service.addPaymentEvent(any()) } answers {
                sentPurchases.add(firstArg<PaymentEvent>().link)
                LSResult.Success(true)
            }
            coEvery { service.addCustomEvents(any()) } answers {
                val events = firstArg<List<io.grovs.model.CustomEvent>>()
                events.forEach { sentCustom.add(it.link) }
                LSResult.Success(BatchEventsResponse(accepted = events.size, rejected = 0))
            }
            manager = GrovsManager(app, app, context, "test", service, events, helper, custom,
                clipboardHandler = ClipboardHandler(service, cache, clipboard, emptyList()))
            manager.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED
            manager.attributionScope = deadlineClock.backgroundScope
        }

        suspend fun assertFutureLink(expected: String?) {
            manager.track("after_resolution", null, null)
            assertEquals(expected, customStorage.getEvents().last().link)
            assertEquals(expected, events.linkForFutureActions)
        }

        /// A commit or a release only queues its delivery. Waits for everything queued so far by
        /// queueing one more flush behind it, so a test can check what was sent.
        suspend fun awaitDeliveries() = events.flush()

        /// Fires the attribution deadline. Releasing the hold queues a delivery on EventsManager's
        /// delivery worker, which runs on real IO threads alongside EventsStorage, so its effects
        /// land asynchronously and a single runCurrent() can miss them. Drain in real time until
        /// [until] reports the release actually happened, rather than assuming a fixed budget is
        /// enough; fail loudly if it never does.
        ///
        /// [until] only has to observe *some* effect of the release (an event landing in rig.sent,
        /// say) — the delivery that produced it is still mid-flight behind it (removeEvents(),
        /// sendPaymentEventsToBackend()), and the deadline coroutine still has to leave
        /// GrovsManager.resolutionMutex on deadlineClock, so stopping the instant [until] turns true
        /// would leave that lock held with nothing left to pump deadlineClock — exactly the kind of
        /// hang this helper exists to avoid. Keep draining a bit longer after [until] is satisfied
        /// to let that tail run too.
        fun releaseDeadline(afterMs: Long, until: () -> Boolean) {
            deadlineClock.advanceTimeBy(afterMs)
            val deadlineAt = System.currentTimeMillis() + 5_000
            while (!until() && System.currentTimeMillis() < deadlineAt) {
                deadlineClock.runCurrent()
                Thread.sleep(5)
            }
            assertTrue("releaseDeadline(${afterMs}ms) did not observe its completion condition within 5s", until())
            repeat(40) {
                deadlineClock.runCurrent()
                Thread.sleep(5)
            }
        }
    }

    @Test
    fun `a direct link wins over a suspended clipboard status and its old deadline`() = runTest {
        val rig = Rig(freshInstall = true)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        coEvery { rig.service.clipboardStatus() } coAnswers {
            started.complete(Unit)
            gate.await()
            LSResult.Success(true)
        }
        try {
            val old = async { rig.manager.handleIntent(Intent(), true) }
            started.await()
            assertEquals(directUrl, rig.manager.handleIntent(Intent().setData(Uri.parse(directUrl)), false).details?.link)
            gate.complete(Unit)
            assertNull(old.await().details)
            rig.deadlineClock.advanceTimeBy(30_000)
            rig.deadlineClock.runCurrent()
            rig.assertFutureLink(directUrl)
            assertEquals(0, rig.clipboard.readCount)
        } finally { rig.manager.close() }
    }

    @Test
    fun `a stale clipboard match cannot clear the clipboard or replace a direct link`() = runTest {
        val rig = Rig(freshInstall = true)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        coEvery { rig.service.payloadWithLinkFor(match { it.url == clipboardUrl }) } coAnswers {
            started.complete(Unit)
            gate.await()
            LSResult.Success(DeeplinkDetails("https://demo.sqd.link/older", null, null))
        }
        try {
            val old = async { rig.manager.handleIntent(Intent(), true) }
            started.await()
            rig.manager.handleIntent(Intent().setData(Uri.parse(directUrl)), false)
            gate.complete(Unit)
            assertNull(old.await().details)
            rig.assertFutureLink(directUrl)
            assertEquals(0, rig.clipboard.clearCount)
        } finally { rig.manager.close() }
    }

    @Test
    fun `a stale fingerprint result cannot replace a newer direct link`() = runTest {
        val rig = Rig(freshInstall = true)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        coEvery { rig.service.payloadFor(any()) } coAnswers {
            started.complete(Unit)
            gate.await()
            LSResult.Success(DeeplinkDetails("https://demo.sqd.link/older", null, null))
        }
        try {
            val old = async { rig.manager.handleIntent(Intent(), true) }
            started.await()
            rig.manager.handleIntent(Intent().setData(Uri.parse(directUrl)), false)
            gate.complete(Unit)
            assertNull(old.await().details)
            rig.assertFutureLink(directUrl)
            coVerify(exactly = 0) { rig.service.clipboardStatus() }
        } finally { rig.manager.close() }
    }

    @Test
    fun `queued custom events receive only the resolved link`() = runTest {
        val rig = Rig(freshInstall = false)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        coEvery { rig.service.payloadWithLinkFor(any()) } coAnswers {
            started.complete(Unit)
            gate.await()
            LSResult.Success(DeeplinkDetails(directUrl, null, null))
        }
        try {
            rig.manager.track("before_lookup", null, null)
            val request = async { rig.manager.handleIntent(Intent().setData(Uri.parse("myapp://open?referrer=unverified")), true) }
            started.await()
            rig.manager.track("during_lookup", null, null)
            assertTrue(rig.customStorage.getEvents().all { it.link == null })
            gate.complete(Unit)
            request.await()
            assertEquals(listOf(directUrl, directUrl), rig.customStorage.getEvents().map { it.link })
        } finally { rig.manager.close() }
    }

    @Test
    fun `a rejected input is never attributed to queued custom events`() = runTest {
        val rig = Rig(freshInstall = false)
        coEvery { rig.service.payloadWithLinkFor(any()) } returns LSResult.Success(empty)
        try {
            rig.manager.track("before_lookup", null, null)
            assertNull(rig.manager.handleIntent(Intent().setData(Uri.parse("myapp://open?referrer=unverified")), false).details)
            assertNull(rig.customStorage.getEvents().single().link)
        } finally { rig.manager.close() }
    }

    @Test
    fun `install stays queued past the old delay while fingerprint matching is pending`() = runTest {
        val rig = Rig(freshInstall = true)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        coEvery { rig.service.payloadFor(any()) } coAnswers {
            started.complete(Unit)
            gate.await()
            LSResult.Success(empty)
        }
        try {
            rig.events.logAppLaunchEvents()
            val request = async { rig.manager.handleIntent(Intent(), true) }
            started.await()
            rig.deadlineClock.advanceTimeBy(20_000)
            rig.deadlineClock.runCurrent()
            rig.events.onAppForegrounded()
            assertTrue(rig.sent.none { it.first == EventType.INSTALL })
            assertTrue(rig.events.eventsHeld)
            gate.complete(Unit)
            assertEquals(directUrl, request.await().details?.link)
            rig.awaitDeliveries()
            assertEquals(listOf(EventType.INSTALL to clipboardUrl), rig.sent.filter { it.first == EventType.INSTALL })
        } finally { rig.manager.close() }
    }

    @Test
    fun `deadline releases install during fingerprint lookup without restarting for clipboard`() = runTest {
        val rig = Rig(freshInstall = true)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        coEvery { rig.service.payloadFor(any()) } coAnswers {
            started.complete(Unit)
            gate.await()
            LSResult.Success(empty)
        }
        try {
            rig.events.logAppLaunchEvents()
            val request = async { rig.manager.handleIntent(Intent(), true) }
            started.await()
            rig.releaseDeadline(25_001) { rig.sent.any { it.first == EventType.INSTALL } }
            assertFalse(rig.events.eventsHeld)
            assertEquals(listOf(EventType.INSTALL to null), rig.sent.filter { it.first == EventType.INSTALL })
            gate.complete(Unit)
            assertEquals(directUrl, request.await().details?.link)
            assertFalse(rig.events.eventsHeld)
        } finally { rig.manager.close() }
    }

    @Test
    fun `canceling the active lookup releases its hold and permits another lookup`() = runTest {
        val rig = Rig(freshInstall = true)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        coEvery { rig.service.payloadFor(any()) } coAnswers {
            started.complete(Unit)
            gate.await()
            LSResult.Success(empty)
        }
        try {
            val request = async { rig.manager.handleIntent(Intent(), true) }
            started.await()
            request.cancelAndJoin()
            assertFalse(rig.events.eventsHeld)
            assertEquals(directUrl, rig.manager.handleIntent(Intent().setData(Uri.parse(directUrl)), false).details?.link)
        } finally { rig.manager.close() }
    }

    // ==================== Regressions from the explicit-resolution refactor ====================

    @Test
    fun `a direct link is attributed before its lookup suspends so cancellation keeps it`() = runTest {
        val rig = Rig(freshInstall = false)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        coEvery { rig.service.payloadWithLinkFor(any()) } coAnswers {
            started.complete(Unit)
            gate.await()
            LSResult.Success(DeeplinkDetails(directUrl, null, null))
        }
        try {
            val request = async { rig.manager.handleIntent(Intent().setData(Uri.parse(directUrl)), false) }
            started.await()
            request.cancelAndJoin()
            assertEquals(directUrl, rig.events.linkForFutureActions)
            assertFalse(rig.events.eventsHeld)
        } finally { rig.manager.close() }
    }

    @Test
    fun `a purchase logged during a direct link lookup carries that link`() = runTest {
        val rig = Rig(freshInstall = false)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        coEvery { rig.service.payloadWithLinkFor(any()) } coAnswers {
            started.complete(Unit)
            gate.await()
            LSResult.Success(DeeplinkDetails(directUrl, null, null))
        }
        try {
            val request = async { rig.manager.handleIntent(Intent().setData(Uri.parse(directUrl)), false) }
            started.await()
            rig.manager.logCustomPurchase(PaymentEventType.BUY, 100, "USD", "sku", InstantCompat.now())
            gate.complete(Unit)
            request.await()
            rig.awaitDeliveries()
            assertEquals(listOf(directUrl), rig.sentPurchases)
        } finally { rig.manager.close() }
    }

    @Test
    fun `a purchase logged during a fingerprint lookup is backfilled with the resolved link`() = runTest {
        val rig = Rig(freshInstall = false)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        coEvery { rig.service.payloadFor(any()) } coAnswers {
            started.complete(Unit)
            gate.await()
            LSResult.Success(DeeplinkDetails(directUrl, null, null))
        }
        try {
            val request = async { rig.manager.handleIntent(Intent(), false) }
            started.await()
            rig.manager.logCustomPurchase(PaymentEventType.BUY, 100, "USD", "sku", InstantCompat.now())
            assertTrue(rig.sentPurchases.isEmpty())
            gate.complete(Unit)
            request.await()
            rig.awaitDeliveries()
            assertEquals(listOf(directUrl), rig.sentPurchases)
        } finally { rig.manager.close() }
    }

    @Test
    fun `custom events do not flush while a lookup is pending and carry its link afterwards`() = runTest {
        val rig = Rig(freshInstall = false)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        coEvery { rig.service.payloadFor(any()) } coAnswers {
            started.complete(Unit)
            gate.await()
            LSResult.Success(DeeplinkDetails(directUrl, null, null))
        }
        try {
            val request = async { rig.manager.handleIntent(Intent(), false) }
            started.await()
            rig.manager.track("during_lookup", null, null)
            rig.custom.flush()
            assertTrue(rig.sentCustom.isEmpty())
            gate.complete(Unit)
            request.await()
            rig.custom.flush()
            assertEquals(listOf(directUrl), rig.sentCustom)
        } finally { rig.manager.close() }
    }

    @Test
    fun `a rejected direct link leaves a parked clipboard flow in charge of install`() = runTest {
        val rig = Rig(freshInstall = true)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        coEvery { rig.service.clipboardStatus() } coAnswers {
            started.complete(Unit)
            gate.await()
            LSResult.Success(true)
        }
        coEvery { rig.service.payloadWithLinkFor(match { it.url == directUrl }) } returns LSResult.Success(empty)
        try {
            rig.events.logAppLaunchEvents()
            val old = async { rig.manager.handleIntent(Intent(), true) }
            started.await()
            assertNull(rig.manager.handleIntent(Intent().setData(Uri.parse(directUrl)), false).details)
            assertTrue(rig.events.eventsHeld)
            assertTrue(rig.sent.none { it.first == EventType.INSTALL })
            gate.complete(Unit)
            assertEquals(directUrl, old.await().details?.link)
            rig.events.onAppForegrounded()
            assertEquals(listOf(EventType.INSTALL to clipboardUrl), rig.sent.filter { it.first == EventType.INSTALL })
        } finally { rig.manager.close() }
    }

    @Test
    fun `a direct link arriving after the deadline is held until it resolves`() = runTest {
        val rig = Rig(freshInstall = true)
        val fingerprintStarted = CompletableDeferred<Unit>()
        val fingerprintGate = CompletableDeferred<Unit>()
        val directStarted = CompletableDeferred<Unit>()
        val directGate = CompletableDeferred<Unit>()
        coEvery { rig.service.payloadFor(any()) } coAnswers {
            fingerprintStarted.complete(Unit)
            fingerprintGate.await()
            LSResult.Success(empty)
        }
        coEvery { rig.service.payloadWithLinkFor(match { it.url == directUrl }) } coAnswers {
            directStarted.complete(Unit)
            directGate.await()
            LSResult.Success(DeeplinkDetails(directUrl, null, null))
        }
        try {
            // logAppLaunchEvents() gives the deadline's flush() an INSTALL to send, so
            // releaseDeadline has a real, observable signal that the release actually completed
            // (not just that eventsHeld flipped synchronously) before the next lookup starts.
            rig.events.logAppLaunchEvents()
            val old = async { rig.manager.handleIntent(Intent(), true) }
            fingerprintStarted.await()
            rig.releaseDeadline(25_001) { rig.sent.any { it.first == EventType.INSTALL } }
            assertFalse(rig.events.eventsHeld)

            val direct = async { rig.manager.handleIntent(Intent().setData(Uri.parse(directUrl)), false) }
            directStarted.await()
            assertTrue(rig.events.eventsHeld)
            directGate.complete(Unit)
            assertEquals(directUrl, direct.await().details?.link)
            assertFalse(rig.events.eventsHeld)
            fingerprintGate.complete(Unit)
            assertNull(old.await().details)
            rig.assertFutureLink(directUrl)
        } finally { rig.manager.close() }
    }

    /** Two direct links in flight: whichever the backend answers first is committed and the other,
     *  even if the user opened it later, is stale and never delivered. Staleness is decided at
     *  commit time, not at start time, so a rejected link can still yield to the one still pending. */
    @Test
    fun `the first explicit link to commit wins when the older response arrives first`() = runTest {
        assertFirstCommittedExplicitLinkWins(olderRespondsFirst = true)
    }

    @Test
    fun `the first explicit link to commit wins when the newer response arrives first`() = runTest {
        assertFirstCommittedExplicitLinkWins(olderRespondsFirst = false)
    }

    private suspend fun assertFirstCommittedExplicitLinkWins(olderRespondsFirst: Boolean) = kotlinx.coroutines.coroutineScope {
        val rig = Rig(freshInstall = false)
        val older = "https://demo.sqd.link/product-a"
        val newer = "https://demo.sqd.link/product-b"
        val olderStarted = CompletableDeferred<Unit>()
        val newerStarted = CompletableDeferred<Unit>()
        val olderReply = CompletableDeferred<Unit>()
        val newerReply = CompletableDeferred<Unit>()
        coEvery { rig.service.payloadWithLinkFor(match { it.url == older }) } coAnswers {
            olderStarted.complete(Unit)
            olderReply.await()
            LSResult.Success(DeeplinkDetails(older, mapOf("product" to "a" as Object), null))
        }
        coEvery { rig.service.payloadWithLinkFor(match { it.url == newer }) } coAnswers {
            newerStarted.complete(Unit)
            newerReply.await()
            LSResult.Success(DeeplinkDetails(newer, mapOf("product" to "b" as Object), null))
        }
        try {
            val first = async { rig.manager.handleIntent(Intent().setData(Uri.parse(older)), false) }
            olderStarted.await()
            val second = async { rig.manager.handleIntent(Intent().setData(Uri.parse(newer)), false) }
            newerStarted.await()
            if (olderRespondsFirst) {
                olderReply.complete(Unit)
                first.await()
                newerReply.complete(Unit)
            } else {
                newerReply.complete(Unit)
                second.await()
                olderReply.complete(Unit)
            }
            val winner = if (olderRespondsFirst) older else newer
            val delivered = listOfNotNull(first.await().details, second.await().details)
            assertEquals("Only the first committed destination is delivered; the other is stale", listOf(winner), delivered.map { it.link })
            assertEquals(if (olderRespondsFirst) "a" else "b", delivered.single().data?.get("product"))
            rig.assertFutureLink(winner)
        } finally {
            olderReply.complete(Unit)
            newerReply.complete(Unit)
            rig.manager.close()
        }
    }

    @Test
    fun `old offline events keep their attribution when a new session opens another campaign`() = runTest(timeout = 40.seconds) {
        verifyOfflineSessionAttribution(campaignA = null)
    }

    @Test
    fun `custom events and screens do not inherit the previous session campaign`() = runTest(timeout = 40.seconds) {
        verifyOfflineSessionAttribution(campaignA = "https://demo.sqd.link/campaign-a")
    }

    private suspend fun CoroutineScope.verifyOfflineSessionAttribution(campaignA: String?) {
        val rig = Rig(freshInstall = false)
        val gson = Gson()
        val sessionA = rig.context.sessionId
        val campaignB = "https://demo.sqd.link/campaign-b"
        val started = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Unit>()
        val offline = LSResult.Error(java.io.IOException("offline"))
        coEvery { rig.service.addEvents(any()) } returns offline
        coEvery { rig.service.addPaymentEvent(any()) } returns offline
        coEvery { rig.service.addCustomEvents(any()) } returns offline
        try {
            if (campaignA != null) {
                coEvery { rig.service.payloadWithLinkFor(any()) } returns
                    LSResult.Success(DeeplinkDetails(campaignA, null, null))
                assertEquals(campaignA, rig.manager.handleIntent(
                    Intent().setData(Uri.parse(campaignA)), delayEvents = true
                ).details?.link)
            }
            rig.events.logAppLaunchEvents()
            rig.manager.track("old_checkout", null, null)
            rig.manager.trackScreenView("OldScreen", null)
            rig.manager.logCustomPurchase(PaymentEventType.BUY, 100, "USD", "old_sku", InstantCompat.now())
            rig.events.onAppForegrounded()
            rig.custom.flush()
            assertTrue(rig.storage.getEvents().any { it.event == EventType.APP_OPEN && it.sessionId == sessionA })
            assertEquals(1, rig.storage.getPaymentEvents().size)
            val oldCustom = rig.customStorage.getEvents()
            assertEquals(2, oldCustom.size)
            assertEquals(listOf(campaignA, campaignA), oldCustom.map { it.link })
            val oldCustomPayloads = oldCustom.map { gson.toJson(it) }

            rig.manager.onAppBackgrounded()
            rig.context.markBackgrounded()
            // Advance wall time after backgrounding; persisted events keep their actual earlier timestamps.
            val resumedAt = rig.context.backgroundedAt!!.plusMillis(31 * 60 * 1000L)
            mockkObject(InstantCompat.Companion)
            every { InstantCompat.now() } returns resumedAt
            rig.context.rotateSessionIfNeeded()
            val sessionB = rig.context.sessionId
            assertNotEquals(sessionA, sessionB)

            coEvery { rig.service.payloadWithLinkFor(any()) } coAnswers {
                started.complete(Unit)
                reply.await()
                LSResult.Success(DeeplinkDetails(campaignB, null, null))
            }
            val lookup = async { rig.manager.handleIntent(Intent().setData(Uri.parse(campaignB)), false) }
            started.await()
            rig.events.logAppLaunchEvents()
            rig.manager.track("new_checkout", null, null)
            rig.manager.trackScreenView("NewScreen", null)
            rig.manager.logCustomPurchase(PaymentEventType.BUY, 200, "USD", "new_sku", InstantCompat.now())
            assertEquals("No campaign from session A may pre-attribute session B",
                listOf(null, null), rig.customStorage.getEvents().filter { it.sessionId == sessionB }.map { it.link })

            val sent = mutableListOf<String>()
            fun record(kind: String, session: String?, link: String?) {
                assertTrue("Unexpected session $session", session == sessionA || session == sessionB)
                sent.add("$kind|${if (session == sessionA) "A" else "B"}|$link")
            }
            coEvery { rig.service.addEvents(any()) } answers {
                val events = firstArg<List<Event>>()
                events.forEach { event ->
                    if (event.event == EventType.APP_OPEN) record("lifecycle", event.sessionId, event.link)
                }
                LSResult.Success(BatchEventsResponse(accepted = events.size, rejected = 0))
            }
            coEvery { rig.service.addPaymentEvent(any()) } answers {
                val event = firstArg<PaymentEvent>()
                record("purchase", event.sessionId, event.link)
                LSResult.Success(true)
            }
            coEvery { rig.service.addCustomEvents(any()) } answers {
                val events = firstArg<List<io.grovs.model.CustomEvent>>()
                events.forEach { event ->
                    record(if (event.eventName == "screen_view") "screen" else "custom", event.sessionId, event.link)
                }
                LSResult.Success(BatchEventsResponse(accepted = events.size, rejected = 0))
            }
            reply.complete(Unit)
            lookup.await()
            rig.awaitDeliveries()
            val queued = rig.customStorage.getEvents()
            assertEquals("Earlier queued events keep every field, including IDs and timestamps",
                oldCustomPayloads, queued.filter { it.sessionId == sessionA }.map { gson.toJson(it) })
            assertEquals(listOf(campaignB, campaignB), queued.filter { it.sessionId == sessionB }.map { it.link })
            rig.custom.flush()
            val expected = listOf("lifecycle", "purchase", "custom", "screen").flatMap {
                listOf("$it|A|$campaignA", "$it|B|$campaignB")
            }
            assertEquals("A later campaign must not rewrite the offline session", expected.sorted(), sent.sorted())
        } finally {
            reply.complete(Unit)
            rig.manager.close()
            unmockkObject(InstantCompat.Companion)
        }
    }

    // ==================== Real-world flows not previously covered ====================

    /** The launcher activity's onStart runs again on rotation and whenever the user navigates back to it. */
    private suspend fun GrovsManager.launcherOnStart(intent: Intent) = handleIntent(intent, delayEvents = true, cacheIntent = true)

    @Test
    fun `brief backgrounding retains the campaign for custom events and screens`() = runTest {
        val rig = Rig(freshInstall = false)
        val session = rig.context.sessionId
        try {
            assertEquals(directUrl, rig.manager.launcherOnStart(Intent().setData(Uri.parse(directUrl))).details?.link)
            rig.manager.track("before_background", null, null)
            rig.manager.trackScreenView("BeforeBackground", null)

            rig.manager.onAppBackgrounded()
            rig.context.markBackgrounded()
            rig.context.rotateSessionIfNeeded()
            assertEquals("A brief background does not rotate the session", session, rig.context.sessionId)
            rig.manager.onAppForegrounded()
            rig.manager.track("after_background", null, null)
            rig.manager.trackScreenView("AfterBackground", null)

            val events = rig.customStorage.getEvents()
            assertEquals(listOf("before_background", "screen_view", "after_background", "screen_view"),
                events.map { it.eventName })
            assertEquals(List(4) { session to directUrl }, events.map { it.sessionId to it.link })
        } finally { rig.manager.close() }
    }

    @Test
    fun `returning to the launcher activity keeps the link for later events`() = runTest {
        val rig = Rig(freshInstall = false)
        val launcherIntent = Intent(Intent.ACTION_VIEW, Uri.parse(directUrl))
        try {
            // Cold start from the link.
            assertEquals(directUrl, rig.manager.launcherOnStart(launcherIntent).details?.link)
            rig.assertFutureLink(directUrl)

            // The user opens another screen and comes back, or rotates the phone: onStart fires
            // again with the same intent and the backend has nothing new to match.
            assertNull(rig.manager.launcherOnStart(launcherIntent).details)

            rig.manager.track("viewed_product", null, null)
            rig.manager.logCustomPurchase(PaymentEventType.BUY, 100, "USD", "sku", InstantCompat.now())
            assertEquals(directUrl, rig.customStorage.getEvents().last().link)
            assertEquals(listOf(directUrl), rig.sentPurchases)
        } finally { rig.manager.close() }
    }

    @Test
    fun `queued events stay held while a superseded lookup is still in flight`() = runTest {
        val rig = Rig(freshInstall = false)
        val firstSession = rig.context.sessionId
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        coEvery { rig.service.payloadFor(match { it.sessionId == firstSession }) } coAnswers {
            started.complete(Unit)
            gate.await()
            LSResult.Success(DeeplinkDetails(directUrl, null, null))
        }
        coEvery { rig.service.payloadFor(match { it.sessionId != firstSession }) } returns LSResult.Success(empty)
        try {
            val old = async { rig.manager.launcherOnStart(Intent()) }
            started.await()

            TestFixtures.startNewSession(rig.context)
            assertNull(rig.manager.launcherOnStart(Intent()).details)

            rig.manager.track("while_pending", null, null)
            rig.custom.flush()
            assertEquals("The empty new-session lookup must not release events the older lookup may still attribute",
                emptyList<String?>(), rig.sentCustom)

            gate.complete(Unit)
            assertEquals(directUrl, old.await().details?.link)
            rig.custom.flush()
            assertEquals(listOf(directUrl), rig.sentCustom)
        } finally { rig.manager.close() }
    }

    @Test
    fun `a match arriving after a session rotation attributes the new session consistently`() = runTest {
        val rig = Rig(freshInstall = false)
        val firstSession = rig.context.sessionId
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        // The first lookup is parked (offline, retrying); later ones answer immediately.
        coEvery { rig.service.payloadFor(match { it.sessionId == firstSession }) } coAnswers {
            started.complete(Unit)
            gate.await()
            LSResult.Success(DeeplinkDetails(directUrl, null, null))
        }
        coEvery { rig.service.payloadFor(match { it.sessionId != firstSession }) } returns LSResult.Success(empty)
        try {
            val old = async { rig.manager.launcherOnStart(Intent()) }
            started.await()

            // Backgrounded for longer than the session timeout, then brought back.
            TestFixtures.startNewSession(rig.context)
            assertNotEquals(firstSession, rig.context.sessionId)
            assertNull(rig.manager.launcherOnStart(Intent()).details)
            rig.manager.track("before_late_match", null, null)

            gate.complete(Unit)
            assertEquals(directUrl, old.await().details?.link)
            rig.manager.track("after_late_match", null, null)

            val links = rig.customStorage.getEvents().filter { it.sessionId == rig.context.sessionId }.map { it.eventName to it.link }
            assertEquals(listOf("before_late_match" to directUrl, "after_late_match" to directUrl), links)
        } finally { rig.manager.close() }
    }
    @Test
    fun `revoked lookup cleanup and deadline cannot release the next generation hold`() = runTest {
        val rig = Rig(freshInstall = false)
        val oldGate = CompletableDeferred<Unit>()
        val nextGate = CompletableDeferred<Unit>()
        val oldStarted = CompletableDeferred<Unit>()
        val nextStarted = CompletableDeferred<Unit>()
        val oldUrl = "https://demo.sqd.link/revoked"
        val nextUrl = "https://demo.sqd.link/current"
        coEvery { rig.service.payloadWithLinkFor(match { it.url == oldUrl }) } coAnswers {
            oldStarted.complete(Unit)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { oldGate.await() }
            LSResult.Success(DeeplinkDetails(oldUrl, null, null))
        }
        coEvery { rig.service.payloadWithLinkFor(match { it.url == nextUrl }) } coAnswers {
            nextStarted.complete(Unit)
            nextGate.await()
            LSResult.Success(DeeplinkDetails(nextUrl, null, null))
        }
        try {
            val old = async { rig.manager.handleIntent(Intent().setData(Uri.parse(oldUrl)), false) }
            oldStarted.await()
            rig.deadlineClock.runCurrent()
            rig.deadlineClock.advanceTimeBy(10_000)
            rig.context.settings.sdkEnabled = false
            rig.context.settings.sdkEnabled = true
            val next = async { rig.manager.handleIntent(Intent().setData(Uri.parse(nextUrl)), false) }
            nextStarted.await()
            rig.deadlineClock.runCurrent()
            oldGate.complete(Unit)
            assertNull(old.await().details)
            rig.deadlineClock.advanceTimeBy(15_001)
            rig.deadlineClock.runCurrent()
            assertTrue("Old deadline must not release the new hold", rig.events.eventsHeld)
            rig.manager.track("current_pending", null, null)
            rig.custom.flush()
            assertTrue(rig.sentCustom.isEmpty())
            nextGate.complete(Unit)
            assertEquals(nextUrl, next.await().details?.link)
            rig.custom.flush()
            assertEquals(listOf(nextUrl), rig.sentCustom)
        } finally {
            oldGate.complete(Unit)
            nextGate.complete(Unit)
            rig.manager.close()
        }
    }

    @Test
    fun `new fingerprint result does not wait for a revoked explicit lookup`() = runTest {
        val rig = Rig(freshInstall = false)
        val oldGate = CompletableDeferred<Unit>()
        val oldStarted = CompletableDeferred<Unit>()
        val oldUrl = "https://demo.sqd.link/revoked-explicit"
        val newUrl = "https://demo.sqd.link/current-fingerprint"
        coEvery { rig.service.payloadWithLinkFor(any()) } coAnswers {
            oldStarted.complete(Unit)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { oldGate.await() }
            LSResult.Success(DeeplinkDetails(oldUrl, null, null))
        }
        coEvery { rig.service.payloadFor(any()) } returns LSResult.Success(DeeplinkDetails(newUrl, null, null))
        try {
            val old = async { rig.manager.handleIntent(Intent().setData(Uri.parse(oldUrl)), false) }
            oldStarted.await()
            rig.context.settings.sdkEnabled = false
            rig.context.settings.sdkEnabled = true
            val current = async { rig.manager.handleIntent(Intent(), false) }
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) { current.await() }
            }
            assertEquals(newUrl, result.details?.link)
            assertFalse("The revoked response is still withheld", oldGate.isCompleted)
            oldGate.complete(Unit)
            assertNull(old.await().details)
            rig.assertFutureLink(newUrl)
        } finally { oldGate.complete(Unit); rig.manager.close() }
    }

    @Test
    fun `revoked intent can be retried explicitly with the same object after re-enable`() = runTest {
        val rig = Rig(freshInstall = false)
        val started = CompletableDeferred<Unit>()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(directUrl))
        coEvery { rig.service.payloadWithLinkFor(any()) } coAnswers {
            started.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }
        try {
            val old = async { rig.manager.handleIntent(intent, false, cacheIntent = true) }
            started.await()
            rig.context.settings.sdkEnabled = false
            assertNull(old.await().details)
            rig.context.settings.sdkEnabled = true
            coEvery { rig.service.payloadWithLinkFor(any()) } returns LSResult.Success(DeeplinkDetails(directUrl, null, null))
            assertEquals(directUrl, rig.manager.handleIntent(intent, false, cacheIntent = true).details?.link)
            coVerify(exactly = 2) { rig.service.payloadWithLinkFor(match { it.url == directUrl }) }
        } finally { rig.manager.close() }
    }

}
