package io.grovs.inapp

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Keeps file work off the main thread. A write is readable here at once and reaches [files] on
 * one background thread, in order. Its text is made on that thread, from an immutable snapshot.
 */
internal class InAppQueuedStore(
    private val files: InAppStoring,
    private val executor: ExecutorService = newExecutor(),
) : InAppStoring {
    private val latest = ConcurrentHashMap<InAppRecord, Lazy<String>>()

    override fun read(record: InAppRecord): String? = latest[record]?.value ?: files.read(record)

    override fun write(text: String, record: InAppRecord): Boolean = write(record) { text }

    override fun write(record: InAppRecord, text: () -> String): Boolean {
        val snapshot = lazy(text)
        try {
            executor.execute {
                val saved = try {
                    files.write(snapshot.value, record)
                } catch (e: Exception) {
                    false
                }
                if (!saved) InAppLog.error("Could not save the in-app ${record.fileName}")
            }
        } catch (e: RejectedExecutionException) {
            return false
        }
        latest[record] = snapshot
        return true
    }

    /** Runs [work] on the store's thread, after the writes already queued. False once shut down. */
    fun inOrder(work: () -> Unit): Boolean = try {
        executor.execute(work)
        true
    } catch (e: RejectedExecutionException) {
        false
    }

    /** Writes already queued still finish; later writes are refused. */
    fun shutdown() = executor.shutdown()

    companion object {
        fun newExecutor(): ExecutorService = Executors.newSingleThreadExecutor { work ->
            Thread(work, "grovs-in-app-store").apply { isDaemon = true }
        }
    }
}
