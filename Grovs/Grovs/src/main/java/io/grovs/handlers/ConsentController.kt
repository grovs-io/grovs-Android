package io.grovs.handlers

import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The identity of one `Grovs.configure` call. Managers and services capture it at construction and
 * acquire consent only for it, so a replaced configuration stays retired even when the replacement
 * uses the same API key. Compared by reference.
 *
 * [scope] is the configuration's SDK-owned lifetime: work launched in it is cancelled when the
 * configuration is retired. It is supervised, so one failed operation never cancels its siblings,
 * and a failure is logged and swallowed rather than reaching the host's uncaught-exception handler
 * (which would crash the app). It has no dispatcher of its own: pass one in the launch context.
 */
internal class ConsentConfiguration internal constructor(val serial: Long) {
    internal val lifetime: CompletableJob = SupervisorJob()
    val scope: CoroutineScope = CoroutineScope(
        lifetime + CoroutineExceptionHandler { _, e ->
            DebugLogger.instance.log(LogLevel.ERROR, "Grovs SDK operation failed (configuration $serial): $e")
        }
    )

    override fun toString(): String = "ConsentConfiguration#$serial"
}

/**
 * Proof that consent was granted for [configuration] in consent [generation] when an operation was
 * admitted. Current only while that configuration is active, consent is enabled and no revocation
 * has happened since; once invalid it never becomes current again.
 *
 * Also a coroutine context element: operations started through [runOperation]/[launchOperation]
 * carry it, and nested operations inherit it instead of acquiring fresh consent.
 */
internal class ConsentToken internal constructor(
    val configuration: ConsentConfiguration,
    val generation: Long,
) : AbstractCoroutineContextElement(ConsentToken) {
    companion object Key : CoroutineContext.Key<ConsentToken>

    override fun toString(): String = "ConsentToken(configuration=${configuration.serial}, generation=$generation)"
}

internal enum class RevocationReason {
    /** Consent was not granted, or the configuration was not active, when the operation asked to start. */
    NOT_ADMITTED,

    /** The operation was admitted, then consent was withdrawn. */
    REVOKED,

    /** The operation was admitted, then its configuration was replaced by a later `configure`. */
    CONFIGURATION_RETIRED,
}

/**
 * The distinguishable cancellation cause for consent. A [CancellationException], so an operation
 * ended by it never fails its parent or reaches an uncaught-exception handler. Explicit public API
 * boundaries catch exactly this type to map it to their existing error; ordinary cancellation
 * propagates untouched.
 */
internal class ConsentRevokedException(
    val reason: RevocationReason,
    val token: ConsentToken?,
) : CancellationException("Grovs SDK consent: $reason${token?.let { " ($it)" } ?: ""}")

/** Cancels one registered piece of SDK work. Runs on the controller's cleanup executor, never under its lock. */
internal fun interface ConsentCancellationHandle {
    fun cancel(cause: ConsentRevokedException)
}

/** A live registration. [unregister] is idempotent; a job registration unregisters itself on completion. */
internal class ConsentRegistration internal constructor(
    private val controller: ConsentController,
    private val entry: Any,
) {
    private val released = AtomicBoolean(false)

    @Volatile
    internal var completionHandle: DisposableHandle? = null

    fun unregister() {
        if (!released.compareAndSet(false, true)) return
        controller.unregister(entry)
        completionHandle?.dispose()
    }
}

/** The three local finalizations that may finish after revocation once admitted (plan §3.4). */
internal enum class CommitKind { STORAGE_TRANSACTION, ACKNOWLEDGEMENT, AUTHENTICATION }

/**
 * Ownership of one admitted commit. Admission and revocation are ordered by the controller lock:
 * a permit obtained before a revocation may run to completion, and that revocation's cleanup (and
 * so the next enable's prior work) waits until [close]. Close exactly once, in a `finally`.
 */
internal class CommitPermit internal constructor(
    private val controller: ConsentController,
    val token: ConsentToken,
    val kind: CommitKind,
) {
    private val closed = AtomicBoolean(false)
    private val completion: CompletableJob = Job()

    /** Completes when the permit is closed. */
    val done: Job get() = completion

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        controller.release(this)
        completion.complete()
    }
}

/** Result of [ConsentController.enable] and [ConsentController.revoke]. */
internal sealed class ConsentTransition {
    /** Consent already had the requested value. Nothing was invalidated or scheduled. */
    object Unchanged : ConsentTransition()

    /**
     * A new enabled generation. [priorWork] completes once every earlier revocation's cleanup has
     * finished, including commits admitted before it; resume work should await it asynchronously.
     */
    class Enabled(val generation: Long, val priorWork: Job) : ConsentTransition()

    /** Consent withdrawn. [cleanup] completes when the revoked operations and admitted commits have finished. */
    class Revoked(val revokedGeneration: Long, val cleanup: Job) : ConsentTransition()

    val changed: Boolean get() = this !== Unchanged
}

/** Result of [ConsentController.retireConfiguration]. */
internal class ConfigurationRetirement(
    val retired: ConsentConfiguration,
    val current: ConsentConfiguration,
    val enabled: Boolean,
    /** Completes when the retired configuration's operations, commits and lifetime scope have finished. */
    val cleanup: Job,
)

/**
 * The authoritative consent state: enabled flag, a consent generation that changes on every
 * transition, and the active configuration identity.
 *
 * The lock guards state and registration bookkeeping only. It is never held while calling out:
 * cancellation handles, job cancellation and completion handlers all run after it is released,
 * and the cancellation of revoked work is handed to [cleanupExecutor] so a slow handler cannot
 * delay the caller of [revoke]. Synchronous token invalidation is what stops new side effects;
 * cancellation delivery only stops work that is already waiting.
 */
internal class ConsentController(
    initiallyEnabled: Boolean = true,
    private val cleanupExecutor: Executor = Dispatchers.IO.asExecutor(),
) {
    private class State(val configuration: ConsentConfiguration, val generation: Long, val enabled: Boolean)

    private class Entry(val token: ConsentToken, val handle: ConsentCancellationHandle, val completion: Job?)

    private val lock = Any()

    @Volatile
    private var state = State(ConsentConfiguration(serial = 1), generation = 1, enabled = initiallyEnabled)

    /** Registrations of the current generation only; revocation detaches the whole set. Guarded by [lock]. */
    private var registrations = LinkedHashSet<Entry>()

    /** Admitted, unclosed commit permits. Guarded by [lock]. */
    private val permits = LinkedHashSet<CommitPermit>()

    /** Revocation/retirement cleanups that have not finished. Guarded by [lock]. */
    private val pendingCleanups = LinkedHashSet<Job>()

    val isEnabled: Boolean get() = state.enabled
    val currentConfiguration: ConsentConfiguration get() = state.configuration

    /** The current consent generation. Changes on every transition; exposed for tests and diagnostics. */
    val generation: Long get() = state.generation

    /** A token for [configuration], or null unless it is the active configuration and consent is enabled. */
    fun tryAcquire(configuration: ConsentConfiguration): ConsentToken? {
        val s = state
        return if (s.enabled && s.configuration === configuration) ConsentToken(configuration, s.generation) else null
    }

    fun isCurrent(token: ConsentToken): Boolean = isCurrent(state, token)

    private fun isCurrent(s: State, token: ConsentToken): Boolean =
        s.enabled && s.configuration === token.configuration && s.generation == token.generation

    /** Throws [ConsentRevokedException] unless [token] is current. */
    fun ensureCurrent(token: ConsentToken) {
        if (!isCurrent(token)) throw revocationFor(token)
    }

    /** The cancellation cause for an operation holding [token] that is no longer current. */
    fun revocationFor(token: ConsentToken): ConsentRevokedException {
        val reason = if (state.configuration !== token.configuration) {
            RevocationReason.CONFIGURATION_RETIRED
        } else {
            RevocationReason.REVOKED
        }
        return ConsentRevokedException(reason, token)
    }

    /**
     * Registers [handle] to be cancelled when [token]'s generation is revoked. Returns null, without
     * calling [handle], if [token] is not current. The caller must then not start the work.
     */
    fun register(token: ConsentToken, handle: ConsentCancellationHandle): ConsentRegistration? =
        add(Entry(token, handle, completion = null))

    /**
     * Registers [job] as a child operation of [token]. Revocation cancels it with a
     * [ConsentRevokedException]; completion on any path unregisters it. If [token] is not current,
     * [job] is cancelled immediately and null is returned. Register a lazily started job before
     * starting it so the body never runs unregistered.
     */
    fun register(token: ConsentToken, job: Job): ConsentRegistration? {
        val registration = add(Entry(token, { cause -> job.cancel(cause) }, completion = job))
        if (registration == null) {
            job.cancel(revocationFor(token))
            return null
        }
        // Outside the lock: fires immediately if the job already completed.
        registration.completionHandle = job.invokeOnCompletion { registration.unregister() }
        return registration
    }

    private fun add(entry: Entry): ConsentRegistration? {
        synchronized(lock) {
            if (!isCurrent(state, entry.token)) return null
            registrations.add(entry)
        }
        return ConsentRegistration(this, entry)
    }

    internal fun unregister(entry: Any) {
        synchronized(lock) { registrations.remove(entry) }
    }

    /**
     * Admits one local commit of [kind] for [token], or returns null if [token] is not current.
     * Call immediately before the side effect, not when the public API was called.
     */
    fun tryAdmitCommit(token: ConsentToken, kind: CommitKind): CommitPermit? {
        synchronized(lock) {
            if (!isCurrent(state, token)) return null
            return CommitPermit(this, token, kind).also { permits.add(it) }
        }
    }

    internal fun release(permit: CommitPermit) {
        synchronized(lock) { permits.remove(permit) }
    }

    /** Starts a new enabled generation, or returns [ConsentTransition.Unchanged] if already enabled. */
    fun enable(): ConsentTransition {
        val enabled: State
        val prior: List<Job>
        synchronized(lock) {
            val s = state
            if (s.enabled) return ConsentTransition.Unchanged
            enabled = State(s.configuration, s.generation + 1, enabled = true)
            state = enabled
            prior = pendingCleanups.toList()
        }
        return ConsentTransition.Enabled(enabled.generation, priorWork = allOf(prior))
    }

    /**
     * Withdraws consent: invalidates every token of the current generation, detaches its
     * registrations and schedules their cancellation on the cleanup executor. Returns without
     * waiting for any of it. [ConsentTransition.Unchanged] if already disabled.
     */
    fun revoke(): ConsentTransition {
        val revoked: State
        val detached: List<Entry>
        val admitted: List<CommitPermit>
        val cleanup = Job()
        synchronized(lock) {
            val s = state
            if (!s.enabled) return ConsentTransition.Unchanged
            revoked = s
            state = State(s.configuration, s.generation + 1, enabled = false)
            detached = registrations.toList()
            registrations = LinkedHashSet()
            admitted = permits.toList()
            pendingCleanups.add(cleanup)
        }
        startCleanup(cleanup, detached, admitted, retired = null, reason = RevocationReason.REVOKED)
        return ConsentTransition.Revoked(revoked.generation, cleanup)
    }

    /**
     * Retires the active configuration and starts a new one with consent [enabled]. The old
     * configuration can never acquire again; its registered operations and lifetime scope are
     * cancelled on the cleanup executor. Always a transition, even for the same API key.
     */
    fun retireConfiguration(enabled: Boolean): ConfigurationRetirement {
        val retired: ConsentConfiguration
        val next: ConsentConfiguration
        val detached: List<Entry>
        val admitted: List<CommitPermit>
        val cleanup = Job()
        synchronized(lock) {
            val s = state
            retired = s.configuration
            next = ConsentConfiguration(serial = retired.serial + 1)
            state = State(next, s.generation + 1, enabled)
            detached = registrations.toList()
            registrations = LinkedHashSet()
            admitted = permits.toList()
            pendingCleanups.add(cleanup)
        }
        startCleanup(cleanup, detached, admitted, retired, RevocationReason.CONFIGURATION_RETIRED)
        return ConfigurationRetirement(retired, next, enabled, cleanup)
    }

    /** Completes once every revocation/retirement cleanup started so far has finished. */
    fun pendingWork(): Job = allOf(synchronized(lock) { pendingCleanups.toList() })

    /** Local writes accepted by a previous owner must finish before a replacement uses its storage. */
    internal fun pendingCommits(): Job = allOf(synchronized(lock) { permits.map { it.done } })

    internal fun registrationCount(): Int = synchronized(lock) { registrations.size }

    internal fun outstandingCommitCount(): Int = synchronized(lock) { permits.size }

    private fun startCleanup(
        cleanup: CompletableJob,
        detached: List<Entry>,
        admitted: List<CommitPermit>,
        retired: ConsentConfiguration?,
        reason: RevocationReason,
    ) {
        cleanup.invokeOnCompletion { synchronized(lock) { pendingCleanups.remove(cleanup) } }
        val awaited = admitted.map { it.done }
        if (detached.isEmpty() && retired == null) {
            // Nothing to cancel, so nothing that could block: complete when the admitted commits close.
            completeWhenAll(awaited, cleanup)
            return
        }
        cleanupExecutor.execute {
            for (entry in detached) {
                try {
                    entry.handle.cancel(ConsentRevokedException(reason, entry.token))
                } catch (t: Throwable) {
                    DebugLogger.instance.log(LogLevel.ERROR, "Consent cancellation handler failed: $t")
                }
            }
            retired?.lifetime?.cancel(ConsentRevokedException(RevocationReason.CONFIGURATION_RETIRED, token = null))
            completeWhenAll(
                detached.mapNotNull { it.completion } + awaited + listOfNotNull(retired?.lifetime),
                cleanup,
            )
        }
    }

    private fun allOf(jobs: List<Job>): Job = Job().also { completeWhenAll(jobs, it) }

    private fun completeWhenAll(jobs: List<Job>, barrier: CompletableJob) {
        if (jobs.isEmpty()) {
            barrier.complete()
            return
        }
        val remaining = AtomicInteger(jobs.size)
        for (job in jobs) {
            job.invokeOnCompletion { if (remaining.decrementAndGet() == 0) barrier.complete() }
        }
    }
}
