package io.grovs.handlers

import io.grovs.model.Event
import io.grovs.model.events.PaymentEventType
import io.grovs.utils.InstantCompat

/**
 * Interface for EventsManager to enable dependency injection and testability.
 * Defines all the public methods that are used by GrovsManager and other components.
 */
interface IEventsManager {
    
    /**
     * Called when the app comes to the foreground.
     * Sends pending events to the backend and marks time spent node.
     */
    suspend fun onAppForegrounded()
    
    /**
     * Called when the app goes to the background.
     * Saves the resign timestamp and marks the time spent node as ending.
     */
    fun onAppBackgrounded()

    /**
     * Sends every stored event that is ready to go: system events (excluding open time-spent
     * nodes) in batches, then payment events one by one. Each queue stops at its own first
     * failure and is retried by the next trigger; a failed system batch still lets payments run.
     * Never blocks the calling thread. Concurrent calls are serialized: only one flush is ever
     * actually sending at a time.
     */
    suspend fun flush()

    /**
     * Logs app launch events including install/reactivation and open events.
     */
    suspend fun logAppLaunchEvents()

    suspend fun logInAppPurchase(originalJson: String)

    suspend fun logCustomPurchase(type: PaymentEventType, priceInCents: Int, currency: String, productId: String, startDate: InstantCompat? = InstantCompat.now())

    /**
     * Logs an event and sends it to the backend.
     * @param event The event to log
     */
    suspend fun log(event: Event)
    
    /** The link stamped on events logged from now on. Does not touch stored events or flush. */
    fun setLinkForFutureEvents(link: String?)

    /** Starts a lookup hold. Pending events wait for a resolved link or the attribution deadline. */
    fun beginLinkResolution()

    /** Commits attribution before releasing the lookup hold and allowing queued events to flush. */
    suspend fun completeLinkResolution(link: String, delayEvents: Boolean)

    /** Releases the lookup hold and flushes queued events with whatever link they already carry. */
    suspend fun releaseLinkResolution(delayEvents: Boolean)

    /**
     * Holds every normal and payment event on the device while true, regardless of `delayEvents`
     * or the first-batch delay. Used while link attribution is unresolved.
     * Clearing the hold does not flush; call [releaseLinkResolution] to do both.
     */
    fun setEventsHeld(held: Boolean)
}
