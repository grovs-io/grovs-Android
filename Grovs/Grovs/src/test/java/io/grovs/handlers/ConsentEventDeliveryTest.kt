package io.grovs.handlers

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.grovs.model.BatchEventsResponse
import io.grovs.model.CustomEvent
import io.grovs.service.GatedExecutor
import io.grovs.service.IGrovsService
import io.grovs.service.useConsentController
import io.grovs.storage.ICustomEventsStorage
import io.grovs.utils.LSResult
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Q — event collection and delivery under consent (plan §5, pruned matrix §10).
 *
 * Backed by a real in-memory queue, so every assertion is about what is actually stored, sent and
 * retired rather than about mock call counts alone.
 */
@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [28])
class ConsentEventDeliveryTest {

    private lateinit var context: Context
    private lateinit var service: IGrovsService
    private lateinit var storage: ICustomEventsStorage
    private lateinit var grovsContext: GrovsContext
    private lateinit var manager: CustomEventsManager

    private val stored = mutableListOf<CustomEvent>()
    private val sentBatches = CopyOnWriteArrayList<List<String>>()
    private var gate: GatedExecutor? = null

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxed = true)
        context = RuntimeEnvironment.getApplication()
        service = mockk(relaxed = true)
        storage = mockk(relaxed = true)
        grovsContext = GrovsContext()
        grovsContext.grovsId = "test-grovs-id"

        stored.clear()
        coEvery { storage.addEvent(any()) } answers { stored.add(firstArg()); Unit }
        coEvery { storage.getEvents() } answers { stored.toList() }
        coEvery { storage.removeEvents(any()) } answers {
            val doomed = firstArg<List<CustomEvent>>().map { it.eventId }.toSet()
            stored.removeAll { doomed.contains(it.eventId) }
            Unit
        }
        coEvery { service.addCustomEvents(any()) } answers {
            sentBatches += firstArg<List<CustomEvent>>().map { it.eventName }
            LSResult.Success(BatchEventsResponse(accepted = firstArg<List<CustomEvent>>().size, rejected = 0))
        }
        buildManager()
    }

    @After
    fun tearDown() {
        gate?.open()
        manager.close()
    }

    private fun buildManager() {
        manager = CustomEventsManager(
            context = context,
            grovsContext = grovsContext,
            grovsService = service,
            customEventsStorage = storage,
            startFlushTimer = false,
        )
    }

    /** Holds revocation cleanup, so a parked flush is never cancelled and only its token can stop it. */
    private fun holdCleanup() {
        gate = GatedExecutor().also {
            grovsContext.useConsentController(ConsentController(cleanupExecutor = it))
        }
        buildManager()
    }

    private fun storedNames() = stored.map { it.eventName }

    // ==================== Q01 ====================

    @Test
    fun `Q01 an event tracked while disabled is never stored, and existing records stay readable`() = runTest {
        manager.track("kept", null, null)
        assertEquals(listOf("kept"), storedNames())

        grovsContext.settings.sdkEnabled = false
        manager.track("dropped", null, null)
        advanceUntilIdle()

        assertEquals("nothing new may be collected while disabled", listOf("kept"), storedNames())

        // Re-enabling does not resurrect the dropped call, and the earlier record is untouched.
        grovsContext.settings.sdkEnabled = true
        advanceUntilIdle()
        assertEquals(listOf("kept"), storedNames())
    }

    // ==================== Q02 ====================

    @Test
    fun `Q02 a flush revoked before its send starts posts nothing and removes nothing`() = runTest {
        holdCleanup()
        manager.track("queued", null, null)

        val readReached = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        coEvery { storage.getEvents() } coAnswers {
            readReached.complete(Unit)
            withContext(NonCancellable) { releaseRead.await() }
            stored.toList()
        }

        val flushing = launch { manager.flush() }
        runCurrent()
        readReached.await()

        // Revoked after the queue was read but before any send could be admitted, then granted
        // again: this flush belongs to the old token and must not continue under the new grant.
        grovsContext.settings.sdkEnabled = false
        grovsContext.settings.sdkEnabled = true
        releaseRead.complete(Unit)
        flushing.join()
        advanceUntilIdle()

        assertEquals("no batch may be posted by the revoked flush", 0, sentBatches.size)
        assertEquals("the queue is untouched", listOf("queued"), storedNames())
        coVerify(exactly = 0) { storage.removeEvents(any()) }
    }

    // ==================== Q03' ====================

    @Test
    fun `Q03 a batch whose response is held past revocation keeps its records, with identity intact`() = runTest {
        holdCleanup()
        manager.track("a", null, null)
        val original = stored.single().copy()

        val sendReached = CompletableDeferred<Unit>()
        val releaseSend = CompletableDeferred<Unit>()
        coEvery { service.addCustomEvents(any()) } coAnswers {
            sentBatches += firstArg<List<CustomEvent>>().map { it.eventName }
            sendReached.complete(Unit)
            withContext(NonCancellable) { releaseSend.await() }
            LSResult.Success(BatchEventsResponse(accepted = 1, rejected = 0))
        }

        val flushing = launch { manager.flush() }
        runCurrent()
        sendReached.await()

        grovsContext.settings.sdkEnabled = false
        grovsContext.settings.sdkEnabled = true
        releaseSend.complete(Unit)
        flushing.join()
        advanceUntilIdle()

        assertEquals("one attempt, no follow-up under the new grant", 1, sentBatches.size)
        assertEquals("an unacknowledged record stays queued", listOf("a"), storedNames())
        val retained = stored.single()
        assertEquals("the retained record keeps its id", original.eventId, retained.eventId)
        assertEquals("...its session", original.sessionId, retained.sessionId)
        assertEquals("...its timestamp", original.createdAt, retained.createdAt)
        assertEquals("...and its attribution", original.link, retained.link)
    }

    // ==================== Q04 ====================

    @Test
    fun `Q04 an acknowledgement admitted before revocation retires exactly its own records`() = runTest {
        holdCleanup()
        manager.track("acked", null, null)

        val removing = CompletableDeferred<Unit>()
        val releaseRemove = CompletableDeferred<Unit>()
        coEvery { storage.removeEvents(any()) } coAnswers {
            removing.complete(Unit)
            withContext(NonCancellable) { releaseRemove.await() }
            val doomed = firstArg<List<CustomEvent>>().map { it.eventId }.toSet()
            stored.removeAll { doomed.contains(it.eventId) }
            Unit
        }

        val flushing = launch { manager.flush() }
        runCurrent()
        removing.await()

        // The removal was admitted before this revocation, so it finishes; a new event tracked
        // under the next grant is not part of it and must survive.
        grovsContext.settings.sdkEnabled = false
        grovsContext.settings.sdkEnabled = true
        manager.track("after", null, null)
        releaseRemove.complete(Unit)
        flushing.join()
        advanceUntilIdle()

        assertEquals("only the acknowledged record is retired", listOf("after"), storedNames())
    }

    @Test
    fun `Q04 an acknowledgement that loses the race to revocation keeps the records queued`() = runTest {
        holdCleanup()
        manager.track("a", null, null)

        val sendReached = CompletableDeferred<Unit>()
        val releaseSend = CompletableDeferred<Unit>()
        coEvery { service.addCustomEvents(any()) } coAnswers {
            sentBatches += firstArg<List<CustomEvent>>().map { it.eventName }
            sendReached.complete(Unit)
            withContext(NonCancellable) { releaseSend.await() }
            LSResult.Success(BatchEventsResponse(accepted = 1, rejected = 0))
        }

        val flushing = launch { manager.flush() }
        runCurrent()
        sendReached.await()
        grovsContext.settings.sdkEnabled = false
        releaseSend.complete(Unit)
        flushing.join()
        advanceUntilIdle()

        // The backend may well have taken the batch, but nothing acknowledged it before consent
        // went away: treating cancellation as consumption would lose the records outright.
        assertEquals("records stay queued for a later retry", listOf("a"), storedNames())
        coVerify(exactly = 0) { storage.removeEvents(any()) }
    }

    // ==================== Q07 ====================

    @Test
    fun `Q07 repeated grants leave one active flush timer, and an old generation's tick cannot send`() = runTest {
        val timed = CustomEventsManager(
            context = context,
            grovsContext = grovsContext,
            grovsService = service,
            customEventsStorage = storage,
            timerDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
            flushIntervalMs = 1_000L,
            startFlushTimer = true,
        )
        try {
            timed.track("tick", null, null)
            // Never advanceUntilIdle here: the flush timer is an endless loop, so "idle" never
            // arrives and virtual time would run away. Only bounded advances.
            runCurrent()

            grovsContext.settings.sdkEnabled = false
            grovsContext.settings.sdkEnabled = true
            grovsContext.settings.sdkEnabled = true // repeated grant: no second timer

            val before = sentBatches.size
            testScheduler.advanceTimeBy(1_100L)
            runCurrent()
            val afterOneInterval = sentBatches.size - before

            assertTrue(
                "one interval must produce at most one batch, saw $afterOneInterval",
                afterOneInterval <= 1,
            )

            testScheduler.advanceTimeBy(1_100L)
            runCurrent()
            assertTrue(
                "a second interval must not produce a burst either, saw ${sentBatches.size - before}",
                sentBatches.size - before <= 2,
            )
        } finally {
            timed.close()
        }
    }
}
