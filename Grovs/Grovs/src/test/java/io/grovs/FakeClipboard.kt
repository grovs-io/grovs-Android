package io.grovs

import io.grovs.utils.ClipDescriptionResult
import io.grovs.utils.IClipboard

/** Scripted [IClipboard]. Set the fields, then assert on the counters. */
class FakeClipboard(
    var accessGranted: Boolean = true,
    var description: ClipDescriptionResult = ClipDescriptionResult.MAYBE_URL,
    var text: String? = null,
    /** When set, [describe] and [readText] throw it. */
    var failure: RuntimeException? = null,
    /** When set, [awaitAccess] throws it instead of returning [accessGranted]. */
    var awaitAccessFailure: Throwable? = null,
) : IClipboard {
    var awaitAccessCount = 0
    var describeCount = 0
    var readCount = 0
    var clearCount = 0

    override suspend fun awaitAccess(timeoutMs: Long): Boolean {
        awaitAccessCount++
        awaitAccessFailure?.let { throw it }
        return accessGranted
    }

    override suspend fun describe(): ClipDescriptionResult {
        describeCount++
        failure?.let { throw it }
        return description
    }

    override fun readText(): String? {
        readCount++
        failure?.let { throw it }
        return text
    }

    override fun clear() {
        clearCount++
        text = null
        description = ClipDescriptionResult.NO_CONTENT
    }
}
