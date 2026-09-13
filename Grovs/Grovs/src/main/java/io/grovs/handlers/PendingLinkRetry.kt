package io.grovs.handlers

import android.content.Intent
import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel
import io.grovs.service.ConsentRequestExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * A tapped link whose lookup failed for a reason that may heal, kept for another try.
 *
 * One slot, last tap wins. Only an authenticated lookup fills it, and both transitions that leave
 * the authenticated state (a new configure, consent withdrawn) make its token stale, so it never
 * overlaps the intent parked while logged out: every trigger drops a stale slot before acting.
 *
 * Every field is read and written on the main thread only, like the login retry timer, so a
 * replaced timer is always the cancelled one and the slot never has two owners. [nextSequence]
 * is the exception: it is called from whatever thread hands an intent to the SDK.
 */
internal class PendingLinkRetry(
    private val context: () -> GrovsContext,
    private val currentManager: () -> GrovsManager?,
    private val replay: (Parked) -> Unit,
) {
    /// [sequence] is tap order: an older tap's answer never touches a newer tap's slot.
    internal class Parked(
        val intent: Intent,
        val delayEvents: Boolean,
        val cacheIntent: Boolean,
        val token: ConsentToken,
        val sessionId: String,
        val sequence: Long,
    )

    private val sequences = AtomicLong()

    internal var slot: Parked? = null
        private set

    internal val backoff = RetryBackoff()

    private var timer: Job? = null

    /** Taken by every intent entering the intent path, before it is handled. */
    fun nextSequence(): Long = sequences.incrementAndGet()

    /** A tapped link got no answer that may heal. Replaces an older pending link, never a newer one. */
    fun park(parked: Parked, failure: RequestFailure) {
        slot?.let { if (parked.sequence < it.sequence) return }
        DebugLogger.instance.log(LogLevel.INFO, "Link lookup failed and may heal - keeping the link for a later retry")
        slot = parked
        backoff.record(failure)
        if (!context().isForeground) return
        armTimer(currentManager() ?: return, backoff.delayMs() ?: return)
    }

    /** A tapped link got its final answer. A pending link it is newer than is superseded by it. */
    fun clear(sequence: Long) {
        val parked = slot ?: run {
            // Nothing parked: this final answer either needed no parking, or is the success of the
            // link that was just replayed (its slot was cleared before the replay ran). Either way,
            // no failure is left pending, so the retry window resets.
            backoff.record(null)
            return
        }
        if (parked.sequence > sequence) return
        drop("a newer link replaced it")
    }

    /**
     * Drops a link parked under a consent grant that is no longer current. A new configuration and
     * a withdrawn consent both post this from whatever thread they run on; it is harmless in any
     * order, because a link parked under a later grant has a current token and survives.
     */
    fun dropIfStale() {
        val parked = slot ?: return
        if (!context().consent.isCurrent(parked.token)) drop("consent withdrawn")
    }

    /** Outside the window, replays at once. Inside it, waits out the remainder. */
    fun onForeground() {
        if (slot == null) return
        val manager = currentManager() ?: return
        if (backoff.canAttempt()) {
            DebugLogger.instance.log(LogLevel.INFO, "Retrying the pending link after foreground")
            replayIfStillNeeded(manager)
        } else {
            armTimer(manager, backoff.delayMs() ?: return)
        }
    }

    /** No automatic retries from the background. The slot survives for the next foreground. */
    fun onBackground() = cancelTimer()

    /**
     * A new network fixes only a lookup that failed for lack of one: it does not fix a backend
     * outage, and a flapping connection must not turn into a stream of requests.
     */
    fun onNetworkAvailable() {
        if (backoff.lastFailure != RequestFailure.OFFLINE) return
        val manager = currentManager() ?: return
        cancelTimer()
        DebugLogger.instance.log(LogLevel.INFO, "Retrying the pending link after the network returned")
        replayIfStillNeeded(manager)
    }

    /** The one guard every trigger passes through. */
    private fun replayIfStillNeeded(manager: GrovsManager) {
        val parked = slot ?: return
        if (currentManager() !== manager) return
        val grovsContext = context()
        if (!grovsContext.isForeground) return
        if (manager.authenticationState != GrovsManager.AuthenticationState.AUTHENTICATED) return
        if (!grovsContext.consent.isCurrent(parked.token)) {
            drop("consent withdrawn")
            return
        }
        if (parked.sessionId != grovsContext.sessionId) {
            drop("session ended")
            return
        }
        // Taken before replaying, so a lookup that fails again parks itself instead of looping here.
        slot = null
        replay(parked)
    }

    private fun armTimer(manager: GrovsManager, waitMs: Long) {
        cancelTimer()
        val grovsContext = context()
        timer = grovsContext.consent.launchOperation(manager.configuration, context = grovsContext.serialDispatcher) {
            val self = coroutineContext[Job]
            delay(waitMs + ConsentRequestExecutor.retryJitterMs())
            withContext(Dispatchers.Main) {
                // A replaced timer never acts, even if its cancellation lost a race.
                if (timer !== self) return@withContext
                timer = null
                DebugLogger.instance.log(LogLevel.INFO, "Retrying the pending link")
                replayIfStillNeeded(manager)
            }
        }
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
    }

    private fun drop(reason: String) {
        DebugLogger.instance.log(LogLevel.INFO, "Dropping the pending link: $reason")
        reset()
    }

    /** Unconditionally forgets whatever is parked: the slot, its backoff, and its timer. */
    internal fun reset() {
        slot = null
        backoff.record(null)
        cancelTimer()
    }
}
