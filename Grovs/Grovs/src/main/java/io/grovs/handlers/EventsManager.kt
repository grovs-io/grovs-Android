package io.grovs.handlers

import android.content.Context
import io.grovs.model.DebugLogger
import io.grovs.model.Event
import io.grovs.model.EventType
import io.grovs.model.LogLevel
import io.grovs.service.GrovsService
import io.grovs.service.IGrovsService
import io.grovs.storage.EventsStorage
import io.grovs.storage.IEventsStorage
import io.grovs.storage.ILocalCache
import io.grovs.storage.LocalCache
import io.grovs.utils.DurationCompat
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSResult
import io.grovs.utils.isValidUrl
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant

class EventsManager(
    val context: Context, 
    val grovsContext: GrovsContext, 
    apiKey: String,
    // Dependencies for testing - if null, real implementations are used
    private val grovsService: IGrovsService = GrovsService(context = context, apiKey = apiKey, grovsContext = grovsContext),
    private val eventsStorage: IEventsStorage = EventsStorage(context = context),
    private val localCache: ILocalCache = LocalCache(context = context)
) : IEventsManager {
    // Internal state - exposed for testing
    internal var linkForFutureActions: String? = null
    internal var allowedToSendToBackend = false
    internal var firstRequestTime: InstantCompat? = null
    internal var eventsDelaySeconds = 14

    companion object {
        private const val FIRST_BATCH_EVENTS_SENDING_LEEWAY: Long = 15000
        private const val NUMBER_OF_DAYS_FOR_REACTIVATION: Int = 7
    }

    override suspend fun onAppForegrounded() {
        sendNormalEventsToBackend()
        sendTimeSpentEventsToBackend()
        eventsStorage.markTimeSpentNode(startingNode = true, link = linkForFutureActions)
    }

    override fun onAppBackgrounded() {
        localCache.resignTimestamp = InstantCompat.now()
        linkForFutureActions = null

        GlobalScope.launch {
            eventsStorage.markTimeSpentNode(startingNode = false, endingNode = true, link = null)
        }
    }

    override suspend fun logAppLaunchEvents() {
        addInitialEvents()
        addOpenEvent()
        eventsStorage.markTimeSpentNode(startingNode = true, link = linkForFutureActions)
    }

    /// Logs an event and sends it to the backend.
    /// - Parameter event: The event to log
    override suspend fun log(event: Event) {
        val newEvent = event
        if (newEvent.link == null) {
            newEvent.link = linkForFutureActions
        }

        eventsStorage.addEvent(newEvent)
        sendNormalEventsToBackend()
    }

    /// Sets the link for future actions to associate with new events.
    /// - Parameter link: The link to set
    override suspend fun setLinkToNewFutureActions(link: String?, delayEvents: Boolean) {
        linkForFutureActions = link
        allowedToSendToBackend = !delayEvents
        link?.let {
            addLinkToEvents(link)
            eventsStorage.markTimeSpentNode(startingNode = false, link = link)
        } ?: kotlin.run {
            sendNormalEventsToBackend()
        }
    }

    /// Adds initial events such as install or reactivation events.
    private suspend fun addInitialEvents() {
        addInstallIfNeeded()
        addReactivationIfNeeded()

        localCache.numberOfOpens += 1
    }

    /// Logs an install event if it's the first app launch.
    private suspend fun addInstallIfNeeded() {
        val numberOfOpens = localCache.numberOfOpens
        if (numberOfOpens == 0) {
            grovsContext.lastSeen?.let {
                val event = Event(event = EventType.REINSTALL, createdAt = InstantCompat.now(), link = linkForFutureActions)
                eventsStorage.addEvent(event)
            } ?: run {
                val event = Event(event = EventType.INSTALL, createdAt = InstantCompat.now(), link = linkForFutureActions)
                eventsStorage.addEvent(event)
            }
        }
    }

    /// Logs a reactivation event if the app was inactive for the specified number of days.
    private suspend fun addReactivationIfNeeded() {
        val lastResignTimestamp = localCache.lastStartTimestamp
        lastResignTimestamp?.let {
            val duration = DurationCompat.between(it, InstantCompat.now())
            val daysBetween = duration.toDays()

            if (daysBetween >= NUMBER_OF_DAYS_FOR_REACTIVATION) {
                val event = Event(EventType.REACTIVATION, InstantCompat.now(), link = linkForFutureActions)
                eventsStorage.addEvent(event)
            }
        }

        localCache.lastStartTimestamp = InstantCompat.now()
    }

    /// Logs an app open event.
    private suspend fun addOpenEvent() {
        val event = Event(event = EventType.APP_OPEN, createdAt = InstantCompat.now(), link = linkForFutureActions)
        eventsStorage.addEvent(event)
    }

    /// Adds a link to all stored events that do not already have one.
    /// - Parameter link: The link to add
    private suspend fun addLinkToEvents(link: String) {
        // Add a link to the stored events
        changeStorageEvents { oldEvent ->
            val newEvent = oldEvent
            when (newEvent.event) {
                EventType.APP_OPEN, EventType.VIEW, EventType.OPEN, EventType.INSTALL, EventType.REINSTALL, EventType.REACTIVATION -> {
                    if (newEvent.link?.isValidUrl() != true) {
                        newEvent.link = link
                    }
                }
                EventType.TIME_SPENT -> {}
            }
            newEvent
        }

        sendNormalEventsToBackend()
    }

    /// Changes stored events based on a closure and performs a completion handler.
    /// - Parameter eventHandling: A lambda function that defines how to modify each event
    private suspend fun changeStorageEvents(eventHandling: (oldEvent: Event) -> Event) {
        // Change stored events based on a closure and perform completion
        val events = eventsStorage.getEvents()
        var newEvents = mutableListOf<Event>()

        for (event in events) {
            val newEvent = eventHandling(event)
            newEvents.add(newEvent)
        }

        eventsStorage.addOrReplaceEvents(newEvents)
    }

    /// Sends normal events (non-time-spent) to the backend.
    private fun sendNormalEventsToBackend() = runBlocking {
        checkEventsSendingAllowed()
        if (!allowedToSendToBackend) {
            return@runBlocking
        }

        val events = eventsStorage.getEvents()
        DebugLogger.instance.log(LogLevel.INFO, "Sending regular logs to the backend: $events")

        for (event in events) {
            if (event.event != EventType.TIME_SPENT) {
                val result = grovsService.addEvent(event)
                when (result) {
                    is LSResult.Success -> {
                        eventsStorage.removeEvent(event)
                    }

                    is LSResult.Error -> {
                        DebugLogger.instance.log(LogLevel.INFO, "Failed to send normal: $event error: $result")
                        delay(5000)
                    }
                }
            }
        }
    }

    /// Sends time-spent events to the backend.
    private fun sendTimeSpentEventsToBackend() = runBlocking {
        val events = eventsStorage.getEvents()
        DebugLogger.instance.log(LogLevel.INFO, "Sending time-spent logs to the backend")

        for (event in events) {
            if ((event.event == EventType.TIME_SPENT) && (event.engagementTime != null)) {
                val result = grovsService.addEvent(event)
                when (result) {
                    is LSResult.Success -> {
                        DebugLogger.instance.log(LogLevel.INFO, "Sent time-spent: $event")
                        eventsStorage.removeEvent(event)
                    }

                    is LSResult.Error -> {
                        DebugLogger.instance.log(LogLevel.INFO, "Failed to send time-spent: $event error: $result")
                        delay(5000)
                    }
                }
            }
        }
    }

    private fun checkEventsSendingAllowed() {
        if (firstRequestTime == null) {
            firstRequestTime = InstantCompat.now()

            GlobalScope.launch {
                delay(FIRST_BATCH_EVENTS_SENDING_LEEWAY)
                sendNormalEventsToBackend()
            }
        }

        if (!allowedToSendToBackend) {
            // Check if delay was met
            val now = InstantCompat.now()
            val duration = DurationCompat.between(firstRequestTime ?: InstantCompat.now(), now)
            allowedToSendToBackend = duration.seconds > eventsDelaySeconds
        }
    }
}