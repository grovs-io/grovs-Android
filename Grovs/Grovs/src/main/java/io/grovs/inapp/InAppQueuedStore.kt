package io.grovs.inapp

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Keeps file writes off the main thread. A write is readable here at once and reaches [files] on
 * one background thread, in order. The text is an immutable snapshot taken by the caller.
 */
internal class InAppQueuedStore(
    private val files: InAppStoring,
    private val executor: ExecutorService = newExecutor(),
) : InAppStoring {
    private val latest = ConcurrentHashMap<InAppRecord, String>()

    override fun read(record: InAppRecord): String? = latest[record] ?: files.read(record)

    override fun write(text: String, record: InAppRecord): Boolean {
        try {
            executor.execute {
                val saved = try {
                    files.write(text, record)
                } catch (e: Exception) {
                    false
                }
                if (!saved) InAppLog.error("Could not save the in-app ${record.fileName}")
            }
        } catch (e: RejectedExecutionException) {
            return false
        }
        latest[record] = text
        return true
    }

    /** Writes already queued still finish; later writes are refused. */
    fun shutdown() = executor.shutdown()

    companion object {
        fun newExecutor(): ExecutorService = Executors.newSingleThreadExecutor { work ->
            Thread(work, "grovs-in-app-store").apply { isDaemon = true }
        }
    }
}
