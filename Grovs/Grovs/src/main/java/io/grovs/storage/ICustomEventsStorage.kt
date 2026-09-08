package io.grovs.storage

import io.grovs.model.CustomEvent

/**
 * Interface for CustomEventsStorage, to enable dependency injection and testability.
 */
internal interface ICustomEventsStorage {

    /** Adds a custom event, evicting the oldest if the cap is exceeded. */
    suspend fun addEvent(event: CustomEvent)

    /** Removes the given events, identified by event id. */
    suspend fun removeEvents(events: List<CustomEvent>)

    /** Retrieves all stored custom events, dropping any older than the staleness threshold. */
    suspend fun getEvents(): List<CustomEvent>

    /**
     * Rewrites every stored event through [transform]. Read and write happen on the storage's own
     * serial dispatcher, so a concurrent add or remove cannot interleave and lose the update.
     */
    suspend fun updateEvents(transform: (CustomEvent) -> CustomEvent)
}
