package io.grovs.storage

import io.grovs.model.Event
import io.grovs.model.events.PaymentEvent

/**
 * Interface for EventsStorage to enable dependency injection and testability.
 */
interface IEventsStorage {
    
    /**
     * Adds or replaces events in the storage.
     * @param events The events to add or replace.
     */
    suspend fun addOrReplaceEvents(events: List<Event>)
    
    /**
     * Adds an event to the storage.
     * @param event The event to add.
     */
    suspend fun addEvent(event: Event)

    /**
     * Adds a payment event to the storage.
     * @param event The event to add.
     */
    suspend fun addPaymentEvent(event: PaymentEvent)


    /**
     * Marks a time spent node for tracking engagement time.
     * @param startingNode Whether this is a starting node
     * @param endingNode Whether this is an ending node
     * @param link The link associated with this time spent node
     */
    suspend fun markTimeSpentNode(startingNode: Boolean, endingNode: Boolean = false, link: String?)
    
    /**
     * Removes an event from the storage.
     * @param event The event to remove.
     */
    suspend fun removeEvent(event: Event)

    /**
     * Removes a payment event from the storage.
     * @param event The payment event to remove.
     */
    suspend fun removePaymentEvent(event: PaymentEvent)

    /**
     * Retrieves all events from the storage.
     * @return List of events
     */
    suspend fun getEvents(): List<Event>

    /**
     * Retrieves all payment events from the storage.
     * @return List of payment events
     */
    suspend fun getPaymentEvents(): List<PaymentEvent>

    /**
     * Check if we already have an empty time spent event.
     * @return true if there's an empty time spent event
     */
    suspend fun hasEmptyTimeSpentEvent(): Boolean
}
