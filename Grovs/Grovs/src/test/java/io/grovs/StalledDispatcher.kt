package io.grovs

import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/**
 * A dispatcher that accepts work and never runs it.
 *
 * Injected as the SDK's serial dispatcher, it turns "this public call secretly waits on SDK
 * work" from a slow test into a detectable failure: a caller that blocks on the dispatcher
 * hangs until the watchdog releases the queue after [rescueAfterMs], at which point [rescued]
 * is true and the elapsed time is far outside any reasonable main-thread budget.
 */
class StalledDispatcher(private val rescueAfterMs: Long = 2_000L) : CoroutineDispatcher() {
    private val queue = LinkedBlockingQueue<Runnable>()
    private val watchdog = AtomicReference<Thread?>(null)

    /** Number of blocks handed to this dispatcher. */
    val dispatched = AtomicInteger(0)

    /** Number of blocks that actually ran (always 0 unless the watchdog had to rescue a hang). */
    val executed = AtomicInteger(0)

    /** True once the watchdog had to release the queue because something was waiting on it. */
    @Volatile
    var rescued = false
        private set

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue.add(block)
        dispatched.incrementAndGet()
        startWatchdog()
    }

    private fun startWatchdog() {
        if (watchdog.get() != null) return
        val thread = Thread({
            try {
                Thread.sleep(rescueAfterMs)
                rescued = true
                while (!Thread.currentThread().isInterrupted) {
                    val block = queue.take()
                    executed.incrementAndGet()
                    block.run()
                }
            } catch (_: InterruptedException) {
                // shutdown
            }
        }, "grovs-stalled-dispatcher-watchdog").apply { isDaemon = true }
        if (watchdog.compareAndSet(null, thread)) thread.start()
    }

    /** Stops the watchdog; queued work is discarded. */
    fun shutdown() {
        watchdog.getAndSet(null)?.interrupt()
        queue.clear()
    }
}
