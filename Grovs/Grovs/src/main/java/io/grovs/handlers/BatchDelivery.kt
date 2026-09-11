package io.grovs.handlers

import io.grovs.model.BatchEventsResponse
import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel
import io.grovs.service.HttpStatusException
import io.grovs.utils.LSResult

private const val MAX_LOGGED_NAME = 64

/** Statuses that refuse a batch as sent (400 Bad Request, 413 Payload Too Large): resending it can never succeed. */
private val BATCH_REFUSALS = setOf(400, 413)

/**
 * Sends [events] as one batch. A batch the backend refuses as a request (400 or 413) can never
 * succeed as sent, so it is split in halves and each half is sent on its own, in order, until the
 * refused events stand alone. Any other failure stops the delivery and keeps what is left for the
 * next flush.
 *
 * An event refused on its own is dropped only when the backend accepted some other part of this
 * delivery, which proves the refusal is about that event rather than the endpoint. A backend that
 * refuses every request drops nothing: its events stay queued like after any other failure.
 *
 * [retire] removes events that left the queue and returns false when it may not (consent was
 * withdrawn). [canContinue] is checked before every request after the first. Returns true when
 * every event left the queue.
 */
internal suspend fun <T> deliverInHalves(
    label: String,
    events: List<T>,
    describe: (T) -> String,
    send: suspend (List<T>) -> LSResult<BatchEventsResponse>,
    retire: suspend (List<T>) -> Boolean,
    canContinue: () -> Boolean,
): Boolean {
    var anyAccepted = false
    val refusedAlone = mutableListOf<T>()

    /** Delivers [part]; false when the delivery must stop and keep what is left. */
    suspend fun deliver(part: List<T>): Boolean {
        val failure = when (val result = send(part)) {
            is LSResult.Success -> {
                anyAccepted = true
                return retire(part)
            }
            is LSResult.Error -> result.exception
        }
        if (failure !is HttpStatusException || failure.code !in BATCH_REFUSALS) {
            DebugLogger.instance.log(LogLevel.INFO, "$label batch failed, keeping for retry: ${failure.message}")
            return false
        }
        if (part.size == 1) {
            refusedAlone += part
            return true
        }
        val middle = (part.size + 1) / 2
        return canContinue() && deliver(part.take(middle)) && canContinue() && deliver(part.drop(middle))
    }

    val finished = deliver(events)
    if (refusedAlone.isEmpty()) return finished
    if (!anyAccepted) {
        DebugLogger.instance.log(
            LogLevel.INFO,
            "$label batch refused as a whole; keeping ${refusedAlone.size} event(s) for retry",
        )
        return false
    }
    DebugLogger.instance.log(
        LogLevel.ERROR,
        "$label: dropping ${refusedAlone.size} event(s) the backend refuses on their own: " +
            refusedAlone.joinToString { describe(it).take(MAX_LOGGED_NAME) },
    )
    return retire(refusedAlone) && finished
}
