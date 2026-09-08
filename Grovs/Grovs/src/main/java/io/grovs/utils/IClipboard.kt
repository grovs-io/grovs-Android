package io.grovs.utils

/** Notice-free verdict on the primary clip, read from its description only. */
enum class ClipDescriptionResult {
    /** No primary clip. */
    NO_CONTENT,
    /** Present, but not text, or classified as definitely not a URL. */
    NOT_URL,
    /** Text that may be a URL. Classification unavailable or positive. */
    MAYBE_URL,
    /** The system refused access (no focus on API 29+) or threw. */
    INACCESSIBLE,
}

/**
 * Thin abstraction over the system clipboard so the clipboard flow is testable.
 * Only [readText] shows the Android 12+ "pasted from clipboard" toast.
 */
interface IClipboard {
    /** Waits until the app may read the clipboard (window focus on API 29+). False on timeout or with no activity. */
    suspend fun awaitAccess(timeoutMs: Long): Boolean

    /**
     * Inspects the clip description without reading content. Never triggers the toast.
     * Suspends because on API 29+ telling an empty clipboard from a focus-less call needs a
     * main-thread window-focus check.
     */
    suspend fun describe(): ClipDescriptionResult

    /** Reads item 0 as text, falling back to its URI. Null when empty or inaccessible. Triggers the toast. */
    fun readText(): String?

    /** Empties the clipboard. */
    fun clear()
}
