package io.grovs.handlers

import io.grovs.model.Event

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
     * Logs app launch events including install/reactivation and open events.
     */
    suspend fun logAppLaunchEvents()
    
    /**
     * Logs an event and sends it to the backend.
     * @param event The event to log
     */
    suspend fun log(event: Event)
    
    /**
     * Sets the link for future actions to associate with new events.
     * @param link The link to set
     * @param delayEvents Whether to delay sending events
     */
    suspend fun setLinkToNewFutureActions(link: String?, delayEvents: Boolean)
}
