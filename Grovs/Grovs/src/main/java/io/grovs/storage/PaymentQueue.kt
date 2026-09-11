package io.grovs.storage

import io.grovs.model.events.PaymentEvent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates SDK payment operations across managers, including injected storage that suspends
 * between reads and writes. All SDK payment access goes through this helper. Direct external
 * writes to injected storage are not coordinated.
 *
 * Lock order: EventsManager's delivery worker -> this mutex -> built-in storage dispatcher.
 * Never run HTTP requests or call back into the SDK while holding the queue mutex.
 */
internal class PaymentQueue(private val storage: IEventsStorage) {
    private companion object {
        // Different storage objects can refer to the same queue, so an instance-local lock is unsafe.
        val mutex = Mutex()
    }

    suspend fun add(event: PaymentEvent) = mutex.withLock {
        storage.addPaymentEvent(event)
    }

    suspend fun snapshot(): List<PaymentEvent> = mutex.withLock {
        storage.getPaymentEvents().toList()
    }

    suspend fun remove(event: PaymentEvent) = mutex.withLock {
        storage.removePaymentEvent(event)
    }

    suspend fun attribute(sessionId: String, link: String) = mutex.withLock {
        fun eligible(event: PaymentEvent) = event.sessionId == sessionId && event.link == null
        if (storage is EventsStorage) {
            // Also protects against direct writes through another built-in storage instance.
            storage.updatePaymentEvents { if (eligible(it)) it.link = link }
        } else {
            // Injected storage: the shared mutex covers this entire suspending transaction and
            // every SDK insert/removal, not just the individual storage calls.
            val events = storage.getPaymentEvents()
            if (events.any(::eligible)) {
                events.forEach { if (eligible(it)) it.link = link }
                storage.replacePaymentEvents(events)
            }
        }
    }
}
