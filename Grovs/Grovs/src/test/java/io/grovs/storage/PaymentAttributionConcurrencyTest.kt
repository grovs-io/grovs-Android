package io.grovs.storage

import android.content.Context
import com.google.gson.Gson
import io.grovs.FakeLocalCache
import io.grovs.handlers.EventsManager
import io.grovs.handlers.GrovsContext
import io.grovs.model.events.PaymentEvent
import io.grovs.model.events.PaymentEventType
import io.grovs.service.IGrovsService
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSResult
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real persisted queues, with both the built-in atomic path and a suspending legacy adapter. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PaymentAttributionConcurrencyTest(private val injected: Boolean) {
    companion object {
        private const val SESSION = "current-session"
        private const val LINK = "https://demo.sqd.link/campaign"

        @JvmStatic
        @Parameters(name = "injected={0}")
        fun storageKinds() = listOf(arrayOf(false), arrayOf(true))
    }

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearStorage() {
        context.getSharedPreferences(EventsStorage.GROVS_STORAGE, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun clearMocks() = unmockkAll()

    private fun purchase(id: String, session: String? = SESSION, link: String? = null) = PaymentEvent(
        eventType = PaymentEventType.BUY,
        appId = "io.grovs.test",
        priceCents = 199,
        currency = "USD",
        date = InstantCompat.ofEpochMilli(1_700_000_000_000),
        transactionToken = "transaction-$id",
        originalTransactionId = 123,
        productId = id,
        store = true,
        sessionId = session,
        link = link,
    )

    private class Gate : AutoCloseable {
        val entered = CompletableDeferred<Unit>()
        private val released = CompletableDeferred<Unit>()
        private val blockingRelease = CountDownLatch(1)

        suspend fun suspendHere() {
            entered.complete(Unit)
            released.await()
        }

        fun blockHere() {
            entered.complete(Unit)
            check(blockingRelease.await(5, TimeUnit.SECONDS)) { "Attribution gate was not released" }
        }

        override fun close() {
            released.complete(Unit)
            blockingRelease.countDown()
        }
    }

    /** Implements only the public [IEventsStorage] interface, with a genuine suspension after reading. */
    private class LegacyStorage(private val backing: EventsStorage) : IEventsStorage by backing {
        var pauseRead: Gate? = null
        var mutationCalls = 0

        override suspend fun getPaymentEvents(): List<PaymentEvent> {
            val snapshot = backing.getPaymentEvents()
            pauseRead?.suspendHere()
            return snapshot
        }

        override suspend fun addPaymentEvent(event: PaymentEvent) {
            mutationCalls++
            backing.addPaymentEvent(event)
        }

        override suspend fun removePaymentEvent(event: PaymentEvent) {
            mutationCalls++
            backing.removePaymentEvent(event)
        }
    }

    private inner class Rig {
        val first = EventsStorage(context)
        val second = EventsStorage(context)
        val legacyFirst = LegacyStorage(first)
        val legacySecond = LegacyStorage(second)
        val queue = PaymentQueue(if (injected) legacyFirst else first)
        val otherQueue = PaymentQueue(if (injected) legacySecond else second)

        fun pauseAttribution(scope: CoroutineScope, gate: Gate): Deferred<Unit> = if (injected) {
            legacyFirst.pauseRead = gate
            scope.async(start = CoroutineStart.UNDISPATCHED) { queue.attribute(SESSION, LINK) }
        } else {
            // Hold the built-in storage transaction itself to also cover direct writes through
            // another EventsStorage, which do not enter the SDK's PaymentQueue wrapper.
            scope.async(Dispatchers.IO) {
                first.updatePaymentEvents {
                    gate.blockHere()
                    if (it.sessionId == SESSION && it.link == null) it.link = LINK
                }
            }
        }

        suspend fun add(event: PaymentEvent) {
            if (injected) otherQueue.add(event) else second.addPaymentEvent(event)
        }

        suspend fun remove(event: PaymentEvent) {
            if (injected) otherQueue.remove(event) else second.removePaymentEvent(event)
        }

        fun assertContending(operation: Deferred<*>) {
            assertFalse("The mutation must wait for attribution", operation.isCompleted)
            if (injected) {
                assertEquals("The queued mutation must not enter injected storage", 0, legacySecond.mutationCalls)
            }
        }
    }

    // PaymentEvent.equals intentionally ignores payload fields, so compare every serialized field.
    private suspend fun assertStored(vararg expected: PaymentEvent) {
        val actual = EventsStorage(context).getPaymentEvents()
        val gson = Gson()
        assertEquals(expected.size, actual.size)
        assertEquals(
            expected.associate { it.transactionToken to gson.toJson(it) },
            actual.associate { it.transactionToken to gson.toJson(it) },
        )
    }

    private fun bounded(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(10_000) { coroutineScope(block) }
    }

    @Test
    fun `an insert attempted after the attribution snapshot is preserved`() = bounded {
        val rig = Rig()
        val existing = purchase("existing")
        val added = purchase("new")
        rig.first.addPaymentEvent(existing)
        Gate().use { gate ->
            val update = rig.pauseAttribution(this, gate)
            gate.entered.await()
            // Execute through the first suspension before returning: this is an attempted
            // insertion, not a child coroutine that might only start after the gate is released.
            val insert = async(start = CoroutineStart.UNDISPATCHED) { rig.add(added) }
            rig.assertContending(insert)
            gate.close()
            update.await()
            insert.await()
        }
        assertStored(purchase("existing", link = LINK), added)
    }

    @Test
    fun `an insert completed before attribution is included in the update`() = bounded {
        val rig = Rig()
        rig.queue.add(purchase("existing"))
        rig.otherQueue.add(purchase("new"))

        rig.queue.attribute(SESSION, LINK)

        assertStored(purchase("existing", link = LINK), purchase("new", link = LINK))
    }

    @Test
    fun `an acknowledged removal overlapping attribution cannot be resurrected`() = bounded {
        val rig = Rig()
        val acknowledged = purchase("acknowledged")
        rig.first.addPaymentEvent(acknowledged)
        rig.first.addPaymentEvent(purchase("retained"))
        Gate().use { gate ->
            val update = rig.pauseAttribution(this, gate)
            gate.entered.await()
            val removal = async(start = CoroutineStart.UNDISPATCHED) { rig.remove(acknowledged) }
            rig.assertContending(removal)
            gate.close()
            update.await()
            removal.await()
        }
        assertStored(purchase("retained", link = LINK))
    }

    @Test
    fun `attribution changes only a null link in the target session`() = bounded {
        val rig = Rig()
        val linked = purchase("linked", link = "https://demo.sqd.link/original")
        val emptyLink = purchase("empty-link", link = "")
        val older = purchase("older", session = "previous-session")
        val legacy = purchase("legacy", session = null)
        for (event in listOf(purchase("eligible"), linked, emptyLink, older, legacy)) rig.queue.add(event)

        rig.otherQueue.attribute(SESSION, LINK)

        assertStored(purchase("eligible", link = LINK), linked, emptyLink, older, legacy)
    }

    @Test
    fun `a cancelled contender cannot mutate storage or strand queue coordination`() = bounded {
        val rig = Rig()
        rig.first.addPaymentEvent(purchase("existing"))
        Gate().use { gate ->
            val update = rig.pauseAttribution(this, gate)
            gate.entered.await()
            val insert = async(start = CoroutineStart.UNDISPATCHED) { rig.otherQueue.add(purchase("cancelled")) }
            rig.assertContending(insert)
            insert.cancel()
            gate.close()
            update.await()
            insert.join()
            assertTrue(insert.isCancelled)
        }
        rig.otherQueue.add(purchase("subsequent"))
        assertStored(purchase("existing", link = LINK), purchase("subsequent"))
    }

    @Test
    fun `a slow HTTP request does not block persisting another purchase`() = bounded {
        val storage = EventsStorage(context)
        val injectedStorage = if (injected) LegacyStorage(storage) else storage
        val service = mockk<IGrovsService>(relaxed = true)
        val grovsContext = GrovsContext().also { it.markAuthenticated("device", it.consent.currentConfiguration) }
        val sent = CopyOnWriteArrayList<String>()
        Gate().use { response ->
            coEvery { service.addPaymentEvent(any()) } coAnswers {
                val event = firstArg<PaymentEvent>()
                sent.add(event.productId!!)
                if (event.productId == "first") response.suspendHere()
                LSResult.Success(true)
            }
            val manager = EventsManager(context, grovsContext, "test", service, injectedStorage, FakeLocalCache())
            manager.firstRequestTime = InstantCompat.now().minusMillis(20_000)
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                manager.logCustomPurchase(PaymentEventType.BUY, 100, "USD", "first", InstantCompat.ofEpochMilli(1))
            }
            try {
                response.entered.await()
                val second = async(start = CoroutineStart.UNDISPATCHED) {
                    manager.logCustomPurchase(PaymentEventType.BUY, 200, "USD", "second", InstantCompat.ofEpochMilli(2))
                }
                // This snapshot queues behind insertion, but must not wait for the HTTP response.
                val persisted = PaymentQueue(EventsStorage(context)).snapshot()
                assertEquals(setOf("first", "second"), persisted.map { it.productId }.toSet())
                assertFalse("The second flush still waits for the first HTTP response", second.isCompleted)
                response.close()
                first.await()
                second.await()
                assertEquals(listOf("first", "second"), sent)
                assertStored()
            } finally {
                response.close()
                first.cancelAndJoin()
            }
        }
    }
}
