package io.grovs.handlers

/**
 * Interface for CustomEventsManager, to enable dependency injection and testability.
 */
internal interface ICustomEventsManager {

    /**
     * Sanitizes and persists a tracked event. Event-name validation (blank/reserved names) happens
     * at the API boundary in [GrovsManager.track]; SDK-internal events like screen_view call this
     * directly.
     */
    suspend fun track(name: String, properties: Map<String, Any>?, tags: List<String>?)

    /** Tags merged onto every subsequently tracked event. Pass null to clear. */
    fun setGlobalTags(tags: List<String>?)

    /** Sends up to BATCH_SIZE pending events. Called by the flush timer and by tests. */
    suspend fun flush()

    /** The link attributed to subsequently tracked events in this session. Null clears the cache. */
    fun setLinkForFutureEvents(link: String?, sessionId: String)

    /** Attributes queued, linkless events only after a link was resolved for this session. */
    suspend fun attributePendingEvents(link: String, sessionId: String)

    /** While held, [flush] sends nothing: queued events wait for the pending link lookup. */
    fun setEventsHeld(held: Boolean)

    /** Cancels periodic work owned by this manager. Safe to call more than once. */
    fun close()
}
