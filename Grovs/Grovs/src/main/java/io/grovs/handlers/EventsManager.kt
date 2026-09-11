package io.grovs.handlers

import android.content.Context
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
import io.grovs.storage.PaymentQueue
import io.grovs.utils.AppDetailsHelper
import io.grovs.utils.DurationCompat
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSResult
import io.grovs.utils.isValidUrl
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

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

    private val paymentQueue = PaymentQueue(eventsStorage)

    /// The configuration this manager belongs to. A manager replaced by a later configure() is
    /// retired as an owner, so it can never acquire consent again even for the same project key.
    private val configuration: ConsentConfiguration = grovsContext.consent.currentConfiguration

    // Every flush trigger (foreground, link release, log(), the leeway timer, the attribution
    // deadline) goes through this one worker. A flush suspends on storage and the network, so two
    // running at once would both read the same not-yet-removed events and double-post them. The
    // worker runs them one at a time and lives as long as the configuration.
    private val deliveries = DeliveryWorker(configuration.scope, grovsContext.serialDispatcher) { token -> deliver(token) }

    companion object {
        const val BATCH_SIZE = 50
        private const val FIRST_BATCH_EVENTS_SENDING_LEEWAY: Long = 15000
        private const val NUMBER_OF_DAYS_FOR_REACTIVATION: Int = 7
    }

    override suspend fun onAppForegrounded() {
        val token = grovsContext.consent.workToken(configuration) ?: return
        withContext(token) {
            flush()
            grovsContext.consent.storeIfConsented(configuration) {
                eventsStorage.markTimeSpentNode(startingNode = true, link = linkForFutureActions, sessionId = grovsContext.sessionId)
            }
        }
    }

    override fun onAppBackgrounded() {
        val consent = grovsContext.consent
        val token = consent.tryAcquire(configuration) ?: return
        val permit = consent.tryAdmitCommit(token, CommitKind.STORAGE_TRANSACTION) ?: return
        val timestamp = InstantCompat.now()
        linkForFutureActions = null
        configuration.scope.launch(NonCancellable + grovsContext.serialDispatcher) {
            permit.finish {
                localCache.resignTimestamp = timestamp
                closeEngagementAt(timestamp)
            }
        }
    }

    internal suspend fun closeEngagementAt(timestamp: InstantCompat) {
        if (eventsStorage is EventsStorage) eventsStorage.closeEngagementAt(timestamp)
        else eventsStorage.markTimeSpentNode(startingNode = false, endingNode = true, link = null, sessionId = grovsContext.sessionId)
    }

    internal suspend fun resumeEngagement() {
        grovsContext.consent.storeIfConsented(configuration) {
            eventsStorage.markTimeSpentNode(startingNode = true, link = linkForFutureActions, sessionId = grovsContext.sessionId)
        }
    }

    override suspend fun logAppLaunchEvents() {
        if (eventsStorage is EventsStorage && localCache is LocalCache) {
            eventsStorage.recordLaunch(localCache, grovsContext.lastSeen, linkForFutureActions, grovsContext.sessionId)
            return
        }
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

        if (grovsContext.consent.storeIfConsented(configuration) { eventsStorage.addEvent(newEvent) }) flush()
    }

    /// Logs an in app payment event and sends it to the backend.
    /// - Parameter event: The event to log
    override suspend fun logInAppPurchase(originalJson: String) {
        val token = grovsContext.consent.workToken(configuration) ?: return
        val events = PaymentEvent.fromOriginalJson(originalJson = originalJson)

        if (events.isEmpty()) {
            DebugLogger.instance.log(LogLevel.ERROR, "The provided originalJson seems to be invalid. Please use the string provided by billing library purchase.originalJson")
        }

        withContext(token) {
            for (event in events) logPurchase(event = event.withSessionId(grovsContext.sessionId))
        }
    }

    /// Logs an in app payment event and sends it to the backend.
    /// - Parameter event: The event to log
    override suspend fun logCustomPurchase(type: PaymentEventType, priceInCents: Int, currency: String, productId: String, startDate: InstantCompat?) {
        val token = grovsContext.consent.workToken(configuration) ?: return
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

        withContext(token) { logPurchase(event = event) }
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
        val token = grovsContext.consent.workToken(configuration)
            ?: kotlinx.coroutines.currentCoroutineContext()[ConsentCommit]?.permit?.token ?: return
        // Keep the gate closed while storage is updated. A background flush must not take INSTALL
        // between releasing the hold and applying the resolved link.
        //
        // The rewrite also runs on the delivery worker, between deliveries: a flush already in flight
        // for the unlinked copy could otherwise finish (network round trip, then removeEvents by
        // type+createdAt) after this rewrite swaps in the linked copy, deleting the linked copy under
        // an unlinked key the backend never received.
        try {
            deliveries.runExclusive {
                grovsContext.consent.storeIfConsented(configuration) {
                    linkForFutureActions = link
                    addLinkToEvents(link)
                    addLinkToPaymentEvents(link)
                    eventsStorage.markTimeSpentNode(startingNode = false, link = link, sessionId = grovsContext.sessionId)
                }
            }
        } finally {
            setEventsHeld(false)
            allowedToSendToBackend = !delayEvents
            if (kotlinx.coroutines.currentCoroutineContext()[ConsentCommit] == null && grovsContext.consent.isCurrent(token)) {
                releaseLinkResolution(delayEvents)
            }
        }
    }

    override suspend fun releaseLinkResolution(delayEvents: Boolean) {
        setEventsHeld(false)
        allowedToSendToBackend = !delayEvents
        requestFlush()
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

        if (grovsContext.consent.storeIfConsented(configuration) { paymentQueue.add(newEvent) }) flush()
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
        paymentQueue.attribute(grovsContext.sessionId, link)
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

    override suspend fun requestFlush() {
        deliveries.requestFlush(grovsContext.consent.workToken(configuration) ?: return)
    }

    override suspend fun flush() {
        deliveries.flush(grovsContext.consent.workToken(configuration) ?: return)
    }

    /// One delivery, under the token of the call that asked for it.
    private suspend fun deliver(token: ConsentToken) {
        try {
            grovsContext.consent.runOperation(token) {
                sendSystemEventsToBackend()
                sendPaymentEventsToBackend()
            }
        } catch (_: ConsentRevokedException) {
            // The queue remains available to a new consent operation.
        }
    }

    /// Chunks of BATCH_SIZE. An accepted chunk leaves storage; the first failed chunk ends this
    /// flush and the next trigger retries it. Open time-spent nodes are not ready yet.
    private suspend fun sendSystemEventsToBackend() {
        val consent = grovsContext.consent
        val token = consent.workToken(configuration) ?: return
        if (!canSend(token)) return
        val ready = eventsStorage.getEvents().filter { it.event != EventType.TIME_SPENT || it.engagementTime != null }
        if (ready.isEmpty()) return
        DebugLogger.instance.log(LogLevel.INFO, "Sending ${ready.size} system events to the backend")

        for (chunk in ready.chunked(BATCH_SIZE)) {
            // Re-checked per chunk against this flush's own token: a revocation between chunks stops
            // the next send, and a grant that arrives after it does not resume this flush.
            if (eventsHeld || !consent.isCurrent(token)) return
            when (val result = grovsService.addEvents(chunk)) {
                is LSResult.Success -> if (!retire(consent, token) { eventsStorage.removeEvents(chunk) }) return
                is LSResult.Error -> {
                    DebugLogger.instance.log(LogLevel.INFO, "System events batch failed, keeping for retry: ${result.exception.message}")
                    return
                }
            }
        }
    }

    /**
     * Retires the records an accepted response acknowledged, if the acknowledgement is admitted
     * before revocation. Returns false when it is not: the records then stay queued for a later
     * retry, because a cancellation is not proof the backend consumed them.
     *
     * An admitted removal runs to completion even if consent is withdrawn while it is writing, so
     * storage never ends up having sent records it still believes are pending.
     */
    private suspend fun retire(consent: ConsentController, token: ConsentToken, removal: suspend () -> Unit): Boolean {
        val permit = consent.tryAdmitCommit(token, CommitKind.ACKNOWLEDGEMENT) ?: run {
            DebugLogger.instance.log(LogLevel.INFO, "Consent withdrawn before the acknowledgement was accepted; keeping the records")
            return false
        }
        permit.use { withContext(NonCancellable) { removal() } }
        return true
    }

    /// One request per payment event (the backend has no batch endpoint for them); same
    /// stop-on-first-failure rule.
    private suspend fun sendPaymentEventsToBackend() {
        val consent = grovsContext.consent
        val token = consent.workToken(configuration) ?: return
        if (!canSend(token)) return
        for (event in paymentQueue.snapshot()) {
            if (eventsHeld || !consent.isCurrent(token)) return
            when (val result = grovsService.addPaymentEvent(event)) {
                is LSResult.Success -> if (!retire(consent, token) { paymentQueue.remove(event) }) return
                is LSResult.Error -> {
                    DebugLogger.instance.log(LogLevel.INFO, "Payment event failed, keeping for retry: ${result.exception.message}")
                    return
                }
            }
        }
    }

    /// Enabled, not held, and past the first-batch delay.
    private fun canSend(token: ConsentToken): Boolean {
        if (!grovsContext.consent.isCurrent(token)) return false
        checkEventsSendingAllowed(token)
        return allowedToSendToBackend
    }

    private fun checkEventsSendingAllowed(token: ConsentToken) {
        if (firstRequestTime == null) {
            firstRequestTime = InstantCompat.now()

            // A registered operation of this generation: a revocation cancels the pending leeway
            // flush, and a later grant starts its own rather than inheriting this one.
            grovsContext.consent.launchOperation(token, context = grovsContext.serialDispatcher) {
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
