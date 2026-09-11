package io.grovs.handlers

import android.content.Context
import io.grovs.model.CustomEvent
import io.grovs.model.CustomEventRules
import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel
import io.grovs.service.IGrovsService
import io.grovs.storage.CustomEventsStorage
import io.grovs.storage.ICustomEventsStorage
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

/**
 * Owns consumer-tracked analytics events. Runs parallel to [EventsManager], which owns the SDK's own
 * lifecycle telemetry (install, app_open, time_spent).
 */
internal class CustomEventsManager(
    val context: Context,
    val grovsContext: GrovsContext,
    private val grovsService: IGrovsService,
    // Dependencies for testing - if defaults are used, real implementations are constructed.
    private val customEventsStorage: ICustomEventsStorage = CustomEventsStorage(context = context),
    timerDispatcher: CoroutineDispatcher = grovsContext.serialDispatcher,
    private val flushIntervalMs: Long = FLUSH_INTERVAL_MS,
    startFlushTimer: Boolean = true,
) : ICustomEventsManager {

    private var globalTags: List<String>? = null
    private data class Attribution(val sessionId: String, val link: String)

    @Volatile
    private var currentAttribution: Attribution? = null
    @Volatile
    private var eventsHeld = false
    private val timerScope = CoroutineScope(timerDispatcher + SupervisorJob())

    // serialDispatcher is Dispatchers.IO.limitedParallelism(1), which releases its slot whenever a
    // coroutine suspends. So the periodic timer tick and a flush triggered from GrovsManager can
    // overlap; without this lock both would read the same stored events and POST the same batch twice.
    private val flushMutex = kotlinx.coroutines.sync.Mutex()

    /// The configuration this manager belongs to; a manager replaced by a later configure() can
    /// never acquire consent again, even for the same project key.
    private val configuration: ConsentConfiguration = grovsContext.consent.currentConfiguration

    companion object {
        /** Events sent per flush cycle. */
        const val BATCH_SIZE = 50

        /** How often pending events are flushed. */
        const val FLUSH_INTERVAL_MS = 30_000L
    }

    init {
        if (startFlushTimer) {
            timerScope.launch {
                while (true) {
                    delay(flushIntervalMs)
                    try {
                        flush()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        DebugLogger.instance.log(
                            LogLevel.ERROR,
                            "Failed to flush custom events: ${e.message}"
                        )
                    }
                }
            }
        }
    }

    override fun close() {
        timerScope.cancel()
    }

    override suspend fun track(name: String, properties: Map<String, Any>?, tags: List<String>?) {
        // Checked against this call's own operation, immediately before the record is built and
        // stored: a call whose consent went away between admission and here collects nothing.
        val token = grovsContext.consent.workToken(configuration) ?: run {
            DebugLogger.instance.log(LogLevel.INFO, "SDK consent not granted, dropping event: $name")
            return
        }

        // Capture one session and one immutable attribution snapshot. A session rotation must
        // not stamp the previous campaign onto a new session's events, even before another lookup.
        val sessionId = grovsContext.sessionId
        val attribution = currentAttribution
        val event = CustomEvent(
            eventName = name,
            sessionId = sessionId,
            link = attribution?.takeIf { it.sessionId == sessionId }?.link,
            createdAt = InstantCompat.now(),
            properties = CustomEventRules.sanitizeProperties(properties),
            tags = CustomEventRules.mergeTags(eventTags = tags, globalTags = globalTags),
        )

        if (!grovsContext.consent.isCurrent(token)) {
            DebugLogger.instance.log(LogLevel.INFO, "SDK consent withdrawn, dropping event: $name")
            return
        }
        withContext(token) {
            grovsContext.consent.storeIfConsented(configuration) { customEventsStorage.addEvent(event) }
        }
    }

    override fun setGlobalTags(tags: List<String>?) {
        globalTags = CustomEventRules.sanitizeTags(tags)
    }

    override fun setLinkForFutureEvents(link: String?, sessionId: String) {
        currentAttribution = link?.let { Attribution(sessionId, it) }
    }

    override suspend fun attributePendingEvents(link: String, sessionId: String) {
        try {
            grovsContext.consent.storeIfConsented(configuration) {
                customEventsStorage.updateEvents { event ->
                    if (event.link == null && event.sessionId == sessionId) event.copy(link = link) else event
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLogger.instance.log(LogLevel.ERROR, "Failed to attribute pending custom events: ${e.message}")
        }
    }

    override fun setEventsHeld(held: Boolean) {
        eventsHeld = held
    }

    override suspend fun flush() {
        val consent = grovsContext.consent
        val token = consent.workToken(configuration) ?: return
        try {
            consent.runOperation(token) {
                flushMutex.withLock { flushUnderConsent() }
            }
        } catch (_: ConsentRevokedException) {
            // End this delivery attempt, not the periodic worker that will serve the next grant.
        }
    }

    private suspend fun flushUnderConsent() {
        val consent = grovsContext.consent
        // Disable pauses delivery; nothing leaves the device until a new grant. Events stay queued,
        // and this flush belongs to the token it started with: a later grant runs its own flush
        // rather than resuming this one.
        val token = consent.workToken(configuration) ?: run {
            DebugLogger.instance.log(LogLevel.INFO, "Skipping custom events flush: consent not granted")
            return
        }
        if (eventsHeld) {
            DebugLogger.instance.log(LogLevel.INFO, "Skipping custom events flush: link lookup pending")
            return
        }
        if (grovsContext.grovsId == null ||
            (grovsContext.requiresAuthentication && grovsContext.authenticatedConfiguration !== configuration)) {
            // Without a device id the backend rejects the send as terminal, which would drop the
            // events for good. Leave them queued; a later tick retries once authenticated.
            DebugLogger.instance.log(
                LogLevel.INFO,
                "Skipping custom events flush: not yet authenticated"
            )
            return
        }

        val pending = customEventsStorage.getEvents().take(BATCH_SIZE)
        if (pending.isEmpty()) return

        DebugLogger.instance.log(LogLevel.INFO, "Flushing ${pending.size} custom events")
        if (!consent.isCurrent(token)) {
            DebugLogger.instance.log(LogLevel.INFO, "Skipping custom events flush: consent withdrawn")
            return
        }
        when (val result = grovsService.addCustomEvents(pending)) {
            // Consumed. Items the backend rejected are reported in the response and can never be
            // accepted, so they leave the queue with the rest. The removal is admitted as an
            // acknowledgement: if revocation wins that race the batch stays queued for a retry,
            // because a cancellation is not proof the backend consumed it.
            is LSResult.Success -> {
                val permit = consent.tryAdmitCommit(token, CommitKind.ACKNOWLEDGEMENT)
                if (permit == null) {
                    DebugLogger.instance.log(LogLevel.INFO, "Consent withdrawn before the acknowledgement; keeping the batch")
                    return
                }
                permit.use { withContext(NonCancellable) { customEventsStorage.removeEvents(pending) } }
            }
            // Nothing was consumed. Keep the batch; the next tick retries.
            is LSResult.Error -> DebugLogger.instance.log(
                LogLevel.INFO,
                "Custom events batch failed, keeping for retry: ${result.exception.message}"
            )
        }
    }
}
