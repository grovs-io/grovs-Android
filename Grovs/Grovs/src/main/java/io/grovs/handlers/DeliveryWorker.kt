package io.grovs.handlers

import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Runs one manager's event deliveries on a single coroutine: one at a time, in the order they were
 * asked for. A delivery suspends on storage and on the network, so a single-thread dispatcher cannot
 * keep two of them from reading the same unsent records; a single owner can.
 *
 * [flush] waits for its delivery. [requestFlush] only queues one, so it is safe to call under
 * another lock, and every request made before that delivery starts shares it. [runExclusive] runs
 * other storage work between deliveries, never alongside one.
 */
internal class DeliveryWorker(
    scope: CoroutineScope,
    context: CoroutineContext = EmptyCoroutineContext,
    private val deliver: suspend (ConsentToken) -> Unit,
) {
    private sealed class Task {
        class Flush(val token: ConsentToken, val done: CompletableDeferred<Unit>) : Task()
        object QueuedFlush : Task()
        class Exclusive(val block: suspend () -> Any?, val done: CompletableDeferred<Any?>) : Task()
    }

    // A task still queued when the worker stops must answer its caller rather than leave it waiting.
    private val tasks = Channel<Task>(Channel.UNLIMITED) { abandon(it) }

    /// The token the queued delivery runs under, or null when none is queued. The latest request
    /// wins, so the delivery belongs to the newest caller that asked for it.
    private val queuedToken = AtomicReference<ConsentToken?>(null)

    init {
        scope.launch(context) {
            for (task in tasks) execute(task)
        }.invokeOnCompletion { tasks.cancel() }
    }

    /** Delivers under [token] after everything queued before it, and rethrows the delivery's failure. */
    suspend fun flush(token: ConsentToken) {
        val done = CompletableDeferred<Unit>()
        submit(Task.Flush(token, done))
        done.await()
    }

    /** Queues a delivery under [token] and returns at once. */
    fun requestFlush(token: ConsentToken) {
        if (queuedToken.getAndSet(token) == null) submit(Task.QueuedFlush)
    }

    /**
     * Runs [block] between deliveries and returns its result. It runs with the caller's context
     * elements (its consent token, an admitted commit) and to completion, even if the worker is
     * stopped while it runs.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> runExclusive(block: suspend () -> T): T {
        val callerElements = currentCoroutineContext().minusKey(Job).minusKey(ContinuationInterceptor)
        val done = CompletableDeferred<Any?>()
        submit(Task.Exclusive({ withContext(NonCancellable + callerElements) { block() } }, done))
        return done.await() as T
    }

    private fun submit(task: Task) {
        if (tasks.trySend(task).isFailure) abandon(task)
    }

    private fun abandon(task: Task) {
        when (task) {
            is Task.Flush -> task.done.cancel()
            is Task.Exclusive -> task.done.cancel()
            Task.QueuedFlush -> queuedToken.set(null)
        }
    }

    private suspend fun execute(task: Task) {
        when (task) {
            is Task.Flush -> settle(task.done) { deliver(task.token) }
            is Task.Exclusive -> settle(task.done) { task.block() }
            Task.QueuedFlush -> queuedToken.getAndSet(null)?.let { token -> settle<Unit>(null) { deliver(token) } }
        }
    }

    /**
     * Runs [work] and hands its outcome to [done], or logs a failure nobody is waiting for. A failed
     * task never stops the worker; only the worker's own cancellation does.
     */
    private suspend fun <T> settle(done: CompletableDeferred<T>?, work: suspend () -> T) {
        val outcome = runCatching { work() }
        if (done != null) {
            done.completeWith(outcome)
        } else {
            outcome.exceptionOrNull()?.takeIf { it !is CancellationException }?.let {
                DebugLogger.instance.log(LogLevel.ERROR, "Event delivery failed: ${it.message}")
            }
        }
        currentCoroutineContext().ensureActive()
    }
}
