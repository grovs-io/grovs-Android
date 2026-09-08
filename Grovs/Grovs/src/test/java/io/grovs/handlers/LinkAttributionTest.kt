package io.grovs.handlers

import android.app.Application
import android.content.Intent
import android.net.Uri
import io.grovs.FakeClipboard
import io.grovs.FakeLocalCache
import io.grovs.TestFixtures
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

/** Exercises resolution through real event managers and SharedPreferences storage. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LinkAttributionTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val clipboardUrl = "https://demo.sqd.link/copied?gd=123"
    private val directUrl = "https://demo.sqd.link/direct"
    private val empty = DeeplinkDetails(null, null, null)

    private inner class Rig(freshInstall: Boolean) {
        // Keep deadline time explicit while SharedPreferences work uses real IO threads.
        val deadlineClock = TestScope()
        val service = mockk<IGrovsService>(relaxed = true)
        val context = GrovsContext().also { it.grovsId = "device" }
        val cache = FakeLocalCache(numberOfOpens = if (freshInstall) 0 else 2)
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
            coEvery { service.addEvent(any()) } answers {
                val event = firstArg<Event>()
                sent.add(event.event to event.link)
                LSResult.Success(true)
            }
            coEvery { service.addPaymentEvent(any()) } answers {
                sentPurchases.add(firstArg<PaymentEvent>().link)
                LSResult.Success(true)
            }
            coEvery { service.addCustomEvent(any()) } answers {
                sentCustom.add(firstArg<io.grovs.model.CustomEvent>().link)
                LSResult.Success(true)
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
            assertEquals(directUrl, rig.manager.handleIntent(Intent().setData(Uri.parse(directUrl)), false)?.link)
            gate.complete(Unit)
            assertNull(old.await())
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
            assertNull(old.await())
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
            assertNull(old.await())
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
            assertNull(rig.manager.handleIntent(Intent().setData(Uri.parse("myapp://open?referrer=unverified")), false))
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
            assertEquals(directUrl, request.await()?.link)
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
            rig.deadlineClock.advanceTimeBy(25_001)
            rig.deadlineClock.runCurrent()
            assertFalse(rig.events.eventsHeld)
            assertEquals(listOf(EventType.INSTALL to null), rig.sent.filter { it.first == EventType.INSTALL })
            gate.complete(Unit)
            assertEquals(directUrl, request.await()?.link)
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
            assertEquals(directUrl, rig.manager.handleIntent(Intent().setData(Uri.parse(directUrl)), false)?.link)
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
            assertNull(rig.manager.handleIntent(Intent().setData(Uri.parse(directUrl)), false))
            assertTrue(rig.events.eventsHeld)
            assertTrue(rig.sent.none { it.first == EventType.INSTALL })
            gate.complete(Unit)
            assertEquals(directUrl, old.await()?.link)
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
            val old = async { rig.manager.handleIntent(Intent(), true) }
            fingerprintStarted.await()
            rig.deadlineClock.advanceTimeBy(25_001)
            rig.deadlineClock.runCurrent()
            assertFalse(rig.events.eventsHeld)

            val direct = async { rig.manager.handleIntent(Intent().setData(Uri.parse(directUrl)), false) }
            directStarted.await()
            assertTrue(rig.events.eventsHeld)
            directGate.complete(Unit)
            assertEquals(directUrl, direct.await()?.link)
            assertFalse(rig.events.eventsHeld)
            fingerprintGate.complete(Unit)
            assertNull(old.await())
            rig.assertFutureLink(directUrl)
        } finally { rig.manager.close() }
    }
}
