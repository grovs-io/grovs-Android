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
import kotlinx.coroutines.launch
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
    private var linkForFutureEvents: String? = null
    @Volatile
    private var eventsHeld = false
    private val timerScope = CoroutineScope(timerDispatcher + SupervisorJob())

    // serialDispatcher is Dispatchers.IO.limitedParallelism(1), which releases its slot whenever a
    // coroutine suspends. So the periodic timer tick and a flush triggered from GrovsManager can
    // overlap; without this lock both would read the same stored events and POST the same batch twice.
    private val flushMutex = kotlinx.coroutines.sync.Mutex()

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
        if (!grovsContext.settings.sdkEnabled) {
            DebugLogger.instance.log(LogLevel.INFO, "SDK disabled, dropping event: $name")
            return
        }

        val event = CustomEvent(
            eventName = name,
            sessionId = grovsContext.sessionId,
            link = linkForFutureEvents,
            createdAt = InstantCompat.now(),
            properties = CustomEventRules.sanitizeProperties(properties),
            tags = CustomEventRules.mergeTags(eventTags = tags, globalTags = globalTags),
        )

        customEventsStorage.addEvent(event)
    }

    override fun setGlobalTags(tags: List<String>?) {
        globalTags = CustomEventRules.sanitizeTags(tags)
    }

    override fun setLinkForFutureEvents(link: String?) {
        linkForFutureEvents = link
    }

    override suspend fun attributePendingEvents(link: String, sessionId: String) {
        try {
            customEventsStorage.updateEvents { event ->
                if (event.link == null && event.sessionId == sessionId) {
                    event.copy(link = link)
                } else {
                    event
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

    override suspend fun flush() = flushMutex.withLock {
        if (!grovsContext.settings.sdkEnabled) {
            // Disable pauses delivery; nothing leaves the device until re-enabled. Events stay queued.
            DebugLogger.instance.log(LogLevel.INFO, "Skipping custom events flush: SDK disabled")
            return@withLock
        }
        if (eventsHeld) {
            DebugLogger.instance.log(LogLevel.INFO, "Skipping custom events flush: link lookup pending")
            return@withLock
        }
        if (grovsContext.grovsId == null) {
            // Without a device id the backend rejects the send as terminal, which would drop the
            // events for good. Leave them queued; a later tick retries once authenticated.
            DebugLogger.instance.log(
                LogLevel.INFO,
                "Skipping custom events flush: not yet authenticated"
            )
            return@withLock
        }

        val pending = customEventsStorage.getEvents().take(BATCH_SIZE)
        if (pending.isEmpty()) return@withLock

        DebugLogger.instance.log(LogLevel.INFO, "Flushing ${pending.size} custom events")
        when (val result = grovsService.addCustomEvents(pending)) {
            // Consumed. Items the backend rejected are reported in the response and can never be
            // accepted, so they leave the queue with the rest.
            is LSResult.Success -> customEventsStorage.removeEvents(pending)
            // Nothing was consumed. Keep the batch; the next tick retries.
            is LSResult.Error -> DebugLogger.instance.log(
                LogLevel.INFO,
                "Custom events batch failed, keeping for retry: ${result.exception.message}"
            )
        }
    }
}
