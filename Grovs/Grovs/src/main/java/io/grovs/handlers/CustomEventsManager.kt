package io.grovs.handlers

import android.content.Context
import io.grovs.model.CustomEvent
import io.grovs.model.CustomEventRules
import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel
import io.grovs.model.exceptions.GrovsErrorCode
import io.grovs.model.exceptions.GrovsException
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

    override suspend fun flush() {
        if (!grovsContext.settings.sdkEnabled) {
            // Disable pauses delivery; nothing leaves the device until re-enabled. Events stay queued.
            DebugLogger.instance.log(LogLevel.INFO, "Skipping custom events flush: SDK disabled")
            return
        }
        if (eventsHeld) {
            DebugLogger.instance.log(LogLevel.INFO, "Skipping custom events flush: link lookup pending")
            return
        }
        if (grovsContext.grovsId == null) {
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

        val done = mutableListOf<CustomEvent>()
        for (event in pending) {
            when (val result = grovsService.addCustomEvents(listOf(event))) {
                is LSResult.Success -> done.add(event)
                is LSResult.Error -> {
                    val exception = result.exception
                    val terminal = exception is GrovsException &&
                        exception.errorCode == GrovsErrorCode.EVENT_DISPATCH_ERROR

                    if (terminal) {
                        // The server said no. Retrying can never succeed, so drop it.
                        DebugLogger.instance.log(
                            LogLevel.ERROR,
                            "Dropping custom event ${event.eventName}: ${exception.message}"
                        )
                        done.add(event)
                    } else {
                        // Transient. Keep it and stop this cycle — the next flush retries.
                        DebugLogger.instance.log(
                            LogLevel.INFO,
                            "Custom event ${event.eventName} failed, keeping for retry: ${exception.message}"
                        )
                        break
                    }
                }
            }
        }

        if (done.isNotEmpty()) {
            customEventsStorage.removeEvents(done)
        }
    }
}
