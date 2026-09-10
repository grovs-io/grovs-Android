package io.grovs.handlers

import android.content.Context
import com.google.gson.annotations.SerializedName
import io.grovs.model.DebugLogger
import io.grovs.model.Event
import io.grovs.model.EventType
import io.grovs.model.LogLevel
import io.grovs.model.events.PaymentEvent
import io.grovs.model.events.PaymentEventType
import io.grovs.service.GrovsService
import io.grovs.service.IGrovsService
import io.grovs.storage.EventsStorage
import io.grovs.storage.IEventsStorage
import io.grovs.storage.ILocalCache
import io.grovs.storage.LocalCache
import io.grovs.utils.AppDetailsHelper
import io.grovs.utils.DurationCompat
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSResult
import io.grovs.utils.isValidUrl
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    @Volatile
    internal var eventsHeld = false
    internal var firstRequestTime: InstantCompat? = null
    internal var eventsDelaySeconds = 14

    companion object {
        const val BATCH_SIZE = 50
        private const val FIRST_BATCH_EVENTS_SENDING_LEEWAY: Long = 15000
        private const val NUMBER_OF_DAYS_FOR_REACTIVATION: Int = 7
    }

    override suspend fun onAppForegrounded() {
        flush()
        eventsStorage.markTimeSpentNode(startingNode = true, link = linkForFutureActions, sessionId = grovsContext.sessionId)
    }

    override fun onAppBackgrounded() {
        localCache.resignTimestamp = InstantCompat.now()
        linkForFutureActions = null

        val sessionId = grovsContext.sessionId
        GlobalScope.launch {
            eventsStorage.markTimeSpentNode(startingNode = false, endingNode = true, link = null, sessionId = sessionId)
        }
    }

    override suspend fun logAppLaunchEvents() {
        addInitialEvents()
        addOpenEvent()
        eventsStorage.markTimeSpentNode(startingNode = true, link = linkForFutureActions, sessionId = grovsContext.sessionId)
    }

    /// Logs an event and sends it to the backend.
    /// - Parameter event: The event to log
    override suspend fun log(event: Event) {
        val newEvent = event
        if (newEvent.link == null) {
            newEvent.link = linkForFutureActions
        }

        eventsStorage.addEvent(newEvent)
        flush()
    }

    /// Logs an in app payment event and sends it to the backend.
    /// - Parameter event: The event to log
    override suspend fun logInAppPurchase(originalJson: String) {
        val events = PaymentEvent.fromOriginalJson(originalJson = originalJson)

        if (events.isEmpty()) {
            DebugLogger.instance.log(LogLevel.ERROR, "The provided originalJson seems to be invalid. Please use the string provided by billing library purchase.originalJson")
        }

        for (event in events) {
            logPurchase(event = event.withSessionId(grovsContext.sessionId))
        }
    }

    /// Logs an in app payment event and sends it to the backend.
    /// - Parameter event: The event to log
    override suspend fun logCustomPurchase(type: PaymentEventType, priceInCents: Int, currency: String, productId: String, startDate: InstantCompat?) {
        val applicationId = AppDetailsHelper(context).applicationId
        val event = PaymentEvent(eventType = type,
            appId = applicationId,
            priceCents = priceInCents.toLong(),
            currency = currency,
            date = startDate,
            productId = productId,
            store = false,
            sessionId = grovsContext.sessionId
        )

        logPurchase(event = event)
    }

    override fun setLinkForFutureEvents(link: String?) {
        linkForFutureActions = link
    }

    /// Holds events; a link already committed this session stays in force. The launcher's onStart
    /// re-fires on rotation and back navigation and its lookup may resolve nothing, which must not
    /// unattribute the rest of the session. A commit replaces the link; backgrounding clears it.
    override fun beginLinkResolution() {
        setEventsHeld(true)
    }

    override suspend fun completeLinkResolution(link: String, delayEvents: Boolean) {
        linkForFutureActions = link
        // Keep the gate closed while storage is updated. A background flush must not take INSTALL
        // between releasing the hold and applying the resolved link.
        try {
            addLinkToEvents(link)
            addLinkToPaymentEvents(link)
            eventsStorage.markTimeSpentNode(startingNode = false, link = link, sessionId = grovsContext.sessionId)
        } finally {
            releaseLinkResolution(delayEvents)
        }
    }

    override suspend fun releaseLinkResolution(delayEvents: Boolean) {
        setEventsHeld(false)
        allowedToSendToBackend = !delayEvents
        flush()
    }

    /// Holds or releases the events hold gate. While held, no normal or payment event
    /// leaves the device, regardless of `delayEvents` or the elapsed delay. Clearing the
    /// hold does not flush by itself; call [releaseLinkResolution] to do both.
    override fun setEventsHeld(held: Boolean) {
        eventsHeld = held
        if (held) {
            allowedToSendToBackend = false
        }
    }

    /// Logs a payment event and sends it to the backend.
    /// - Parameter event: The event to log
    private suspend fun logPurchase(event: PaymentEvent) {
        val newEvent = event
        if (newEvent.link == null) {
            newEvent.link = linkForFutureActions
        }

        eventsStorage.addPaymentEvent(newEvent)
        flush()
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
                val event = Event(event = EventType.REINSTALL, createdAt = InstantCompat.now(), link = linkForFutureActions, sessionId = grovsContext.sessionId)
                eventsStorage.addEvent(event)
            } ?: run {
                val event = Event(event = EventType.INSTALL, createdAt = InstantCompat.now(), link = linkForFutureActions, sessionId = grovsContext.sessionId)
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
                val event = Event(EventType.REACTIVATION, InstantCompat.now(), link = linkForFutureActions, sessionId = grovsContext.sessionId)
                eventsStorage.addEvent(event)
            }
        }

        localCache.lastStartTimestamp = InstantCompat.now()
    }

    /// Logs an app open event.
    private suspend fun addOpenEvent() {
        val event = Event(event = EventType.APP_OPEN, createdAt = InstantCompat.now(), link = linkForFutureActions, sessionId = grovsContext.sessionId)
        eventsStorage.addEvent(event)
    }

    /// Stamps the link on this session's stored linkless lifecycle events. Events from earlier
    /// sessions (queued offline) keep whatever resolved back then, like purchases and custom events.
    private suspend fun addLinkToEvents(link: String) {
        val sessionId = grovsContext.sessionId
        changeStorageEvents { oldEvent ->
            val newEvent = oldEvent
            when (newEvent.event) {
                EventType.APP_OPEN, EventType.VIEW, EventType.OPEN, EventType.INSTALL, EventType.REINSTALL, EventType.REACTIVATION -> {
                    if (newEvent.sessionId == sessionId && newEvent.link?.isValidUrl() != true) {
                        newEvent.link = link
                    }
                }
                EventType.TIME_SPENT -> {}
            }
            newEvent
        }
    }

    /// Purchases logged while this session's link was still unresolved earned it too. Earlier
    /// sessions belong to whatever resolved back then, and an attributed purchase is never rewritten.
    private suspend fun addLinkToPaymentEvents(link: String) {
        val sessionId = grovsContext.sessionId
        val events = eventsStorage.getPaymentEvents()
        if (events.none { it.link == null && it.sessionId == sessionId }) return
        events.forEach { if (it.link == null && it.sessionId == sessionId) it.link = link }
        eventsStorage.replacePaymentEvents(events)
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

    override suspend fun flush() {
        sendSystemEventsToBackend()
        sendPaymentEventsToBackend()
    }

    /// Chunks of BATCH_SIZE. An accepted chunk leaves storage; the first failed chunk ends this
    /// flush and the next trigger retries it. Open time-spent nodes are not ready yet.
    private suspend fun sendSystemEventsToBackend() {
        if (!canSend()) return
        val ready = eventsStorage.getEvents().filter { it.event != EventType.TIME_SPENT || it.engagementTime != null }
        if (ready.isEmpty()) return
        DebugLogger.instance.log(LogLevel.INFO, "Sending ${ready.size} system events to the backend")

        for (chunk in ready.chunked(BATCH_SIZE)) {
            if (eventsHeld || !grovsContext.settings.sdkEnabled) return
            when (val result = grovsService.addEvents(chunk)) {
                is LSResult.Success -> eventsStorage.removeEvents(chunk)
                is LSResult.Error -> {
                    DebugLogger.instance.log(LogLevel.INFO, "System events batch failed, keeping for retry: ${result.exception.message}")
                    return
                }
            }
        }
    }

    /// One request per payment event (the backend has no batch endpoint for them); same
    /// stop-on-first-failure rule.
    private suspend fun sendPaymentEventsToBackend() {
        if (!canSend()) return
        for (event in eventsStorage.getPaymentEvents()) {
            if (eventsHeld || !grovsContext.settings.sdkEnabled) return
            when (val result = grovsService.addPaymentEvent(event)) {
                is LSResult.Success -> eventsStorage.removePaymentEvent(event)
                is LSResult.Error -> {
                    DebugLogger.instance.log(LogLevel.INFO, "Payment event failed, keeping for retry: ${result.exception.message}")
                    return
                }
            }
        }
    }

    /// Enabled, not held, and past the first-batch delay.
    private fun canSend(): Boolean {
        if (!grovsContext.settings.sdkEnabled) return false
        checkEventsSendingAllowed()
        return allowedToSendToBackend
    }

    private fun checkEventsSendingAllowed() {
        if (firstRequestTime == null) {
            firstRequestTime = InstantCompat.now()

            GlobalScope.launch(grovsContext.serialDispatcher) {
                delay(FIRST_BATCH_EVENTS_SENDING_LEEWAY)
                flush()
            }
        }

        if (eventsHeld) {
            allowedToSendToBackend = false
            return
        }

        if (!allowedToSendToBackend) {
            // Check if delay was met
            val now = InstantCompat.now()
            val duration = DurationCompat.between(firstRequestTime ?: InstantCompat.now(), now)
            allowedToSendToBackend = duration.seconds > eventsDelaySeconds
        }
    }
}
