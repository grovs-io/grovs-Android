package io.grovs.handlers

import android.os.SystemClock

/**
 * A widening window between automatic retries of one request: 10s after the first failure,
 * doubling per consecutive failure to a 300s ceiling. An offline failure keeps the window at its
 * base, a success resets it, and a refused request never yields an automatic delay.
 */
internal class RetryBackoff {

    /// Elapsed-time source the window is measured against. A seam so tests can move time.
    internal var elapsedMs: () -> Long = { SystemClock.elapsedRealtime() }

    /// Why the last attempt failed, or null after a success or before any attempt.
    @Volatile
    var lastFailure: RequestFailure? = null
        private set

    /// Consecutive non-offline failures. Widens the window.
    private var failureCount = 0

    /// When the next attempt is allowed. Null means "now".
    private var retryAt: Long? = null

    fun canAttempt(): Boolean {
        val at = retryAt ?: return true
        return elapsedMs() >= at
    }

    /// Records an attempt's result. Null means it succeeded.
    fun record(failure: RequestFailure?) {
        lastFailure = failure
        if (failure == null) {
            failureCount = 0
            retryAt = null
            return
        }
        if (failure == RequestFailure.OFFLINE) {
            // Being offline says nothing about the backend or the key, so the window stays at its base.
            retryAt = elapsedMs() + BASE_MS
            return
        }
        failureCount++
        val shift = (failureCount - 1).coerceAtMost(MAX_SHIFT)
        retryAt = elapsedMs() + minOf(BASE_MS shl shift, MAX_MS)
    }

    /**
     * How long an automatic retry waits, or null when nothing should retry on its own: the last
     * attempt succeeded, or the backend refused the request (a refusal never heals by waiting).
     */
    fun delayMs(): Long? {
        val failure = lastFailure ?: return null
        if (failure == RequestFailure.REJECTED) return null
        val at = retryAt ?: return 0L
        return (at - elapsedMs()).coerceAtLeast(0L)
    }

    companion object {
        const val BASE_MS = 10_000L
        const val MAX_MS = 300_000L
        /// Keeps the doubling from overflowing once the ceiling is reached anyway.
        private const val MAX_SHIFT = 16
    }
}
