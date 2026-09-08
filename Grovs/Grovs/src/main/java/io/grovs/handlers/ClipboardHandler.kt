package io.grovs.handlers

import android.net.Uri
import io.grovs.model.AppDetails
import io.grovs.model.DebugLogger
import io.grovs.model.DeeplinkDetails
import io.grovs.model.LogLevel
import io.grovs.service.IGrovsService
import io.grovs.storage.ILocalCache
import io.grovs.utils.ClipDescriptionResult
import io.grovs.utils.IClipboard
import io.grovs.utils.LSResult
import kotlinx.coroutines.CancellationException

/** Terminal outcome of one run of the clipboard-assisted deferred deep link flow. */
internal sealed class ClipboardFlowOutcome {
    /** A Grovs link was on the clipboard and the backend matched it. [clipboardUrl] is the string sent verbatim. */
    data class Matched(val clipboardUrl: String, val details: DeeplinkDetails) : ClipboardFlowOutcome()

    /** Terminal: the flag is cleared for the life of the install. */
    object Resolved : ClipboardFlowOutcome()

    /** Terminal for this run only: a transient failure kept the flag armed. */
    object Retry : ClipboardFlowOutcome()

    /** A run is already in flight; the caller must do nothing. */
    object AlreadyRunning : ClipboardFlowOutcome()
}

/**
 * One-shot first-launch clipboard match, run when the fingerprint resolve returned nothing.
 *
 * Must be constructed before the opens counter is incremented (zero = fresh install or reinstall).
 * Nothing outside the built-in link hosts plus [clipboardDomains] ever leaves the device.
 */
internal class ClipboardHandler(
    private val grovsService: IGrovsService,
    private val localCache: ILocalCache,
    private val clipboard: IClipboard,
    clipboardDomains: List<String>,
) {
    companion object {
        private val LINK_HOST_SUFFIXES = listOf(".sqd.link", ".test-sqd.link", ".grovs.link")
        const val FOCUS_TIMEOUT_MS: Long = 3_000
        /** Matches only a leading scheme (e.g. "https://"), never a "://" embedded further into the string. */
        private val LEADING_SCHEME = Regex("^[a-z][a-z0-9+.-]*://")

        /**
         * Normalizes configured domains so a malformed entry (scheme, path, whitespace, TLD-only)
         * cannot widen the privacy gate. Order is preserved.
         */
        fun normalizeDomains(domains: List<String>?): List<String> {
            return (domains ?: emptyList()).mapNotNull { entry ->
                var host = entry.trim().lowercase()
                host = LEADING_SCHEME.replace(host, "")
                host = host.substringBefore('/')
                host = host.trim('.')
                host.takeIf { it.contains('.') }
            }
        }
    }

    private val clipboardDomains: List<String> = normalizeDomains(clipboardDomains)
    private var running = false
    /** Kept across a failed match so a retry never re-reads the clipboard (and never re-shows the toast). */
    private var cachedClipboardString: String? = null

    init {
        if (localCache.numberOfOpens == 0) {
            localCache.clipboardFlowPending = true
        }
    }

    val isPending: Boolean
        get() = localCache.clipboardFlowPending

    /** Ends the flow for the life of this install. The clipboard must never be touched afterwards. */
    fun markResolved() {
        localCache.clipboardFlowPending = false
    }

    suspend fun runFlow(appDetails: AppDetails): ClipboardFlowOutcome {
        if (!isPending) return ClipboardFlowOutcome.Resolved
        // Re-entry mid-flight (a second onStart) must not release the held INSTALL or stack a valve.
        if (running) return ClipboardFlowOutcome.AlreadyRunning
        running = true

        try {
            when (val status = grovsService.clipboardStatus()) {
                is LSResult.Error -> {
                    DebugLogger.instance.log(LogLevel.INFO, "Clipboard flow - status unavailable, will retry")
                    return ClipboardFlowOutcome.Retry
                }
                is LSResult.Success -> if (!status.data) {
                    DebugLogger.instance.log(LogLevel.INFO, "Clipboard flow - project inactive, skipping clipboard")
                    return resolve()
                }
            }

            // Resolved externally (fingerprint hit / direct link) while the status call was in flight.
            if (!isPending) return ClipboardFlowOutcome.Retry

            // A string cached by an earlier match failure skips detection and the second toast.
            cachedClipboardString?.let { return sendMatch(appDetails, it) }

            val accessGranted = try {
                clipboard.awaitAccess(FOCUS_TIMEOUT_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!accessGranted) {
                DebugLogger.instance.log(LogLevel.INFO, "Clipboard flow - no window focus, will retry")
                return ClipboardFlowOutcome.Retry
            }

            val description = try {
                clipboard.describe()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ClipDescriptionResult.INACCESSIBLE
            }
            when (description) {
                ClipDescriptionResult.NO_CONTENT, ClipDescriptionResult.NOT_URL -> {
                    DebugLogger.instance.log(LogLevel.INFO, "Clipboard flow - no probable web URL")
                    return resolve()
                }
                ClipDescriptionResult.INACCESSIBLE -> {
                    DebugLogger.instance.log(LogLevel.INFO, "Clipboard flow - clipboard inaccessible, will retry")
                    return ClipboardFlowOutcome.Retry
                }
                ClipDescriptionResult.MAYBE_URL -> Unit
            }

            if (!isPending) return ClipboardFlowOutcome.Retry

            // This read shows the API 31+ toast. Null is transient on Android (no denial exists), so retry.
            val clipboardString = try {
                clipboard.readText()
            } catch (e: Exception) {
                null
            }
            if (clipboardString == null) {
                DebugLogger.instance.log(LogLevel.INFO, "Clipboard flow - clipboard unreadable, will retry")
                return ClipboardFlowOutcome.Retry
            }
            if (!isGrovsLink(clipboardString)) {
                DebugLogger.instance.log(LogLevel.INFO, "Clipboard flow - content is not a Grovs link")
                return resolve()
            }

            return sendMatch(appDetails, clipboardString)
        } finally {
            running = false
        }
    }

    private fun resolve(): ClipboardFlowOutcome {
        markResolved()
        return ClipboardFlowOutcome.Resolved
    }

    private suspend fun sendMatch(appDetails: AppDetails, clipboardString: String): ClipboardFlowOutcome {
        return when (val result = grovsService.payloadWithLinkFor(appDetails.copy(url = clipboardString))) {
            is LSResult.Error -> {
                DebugLogger.instance.log(LogLevel.INFO, "Clipboard flow - match request failed, will retry")
                cachedClipboardString = clipboardString
                ClipboardFlowOutcome.Retry
            }
            is LSResult.Success -> {
                cachedClipboardString = null
                when {
                    result.data.link == null -> {
                        // No match: may be the user's own content, leave the clipboard alone.
                        DebugLogger.instance.log(LogLevel.INFO, "Clipboard flow - no match")
                        resolve()
                    }
                    !isPending -> {
                        // Resolved externally while the request was in flight: the link already arrived elsewhere.
                        ClipboardFlowOutcome.Retry
                    }
                    else -> {
                        DebugLogger.instance.log(LogLevel.INFO, "Clipboard flow - matched, clearing clipboard")
                        try {
                            clipboard.clear()
                        } catch (e: Exception) {
                            DebugLogger.instance.log(LogLevel.INFO, "Clipboard flow - clear failed: ${e.message}")
                        }
                        markResolved()
                        ClipboardFlowOutcome.Matched(clipboardUrl = clipboardString, details = result.data)
                    }
                }
            }
        }
    }

    /**
     * Only https URLs on Grovs-owned or configured link hosts, carrying the minted `gd` device
     * param, ever leave the device. A bare project link (shared by a friend, own share sheet) is
     * not click evidence: the preview page always mints `gd`.
     */
    internal fun isGrovsLink(string: String): Boolean {
        val uri = try {
            Uri.parse(string)
        } catch (e: Exception) {
            return false
        }
        if (uri.scheme?.lowercase() != "https") return false
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false

        val gd = try {
            uri.getQueryParameter("gd")
        } catch (e: Exception) {
            null
        }
        if (gd.isNullOrEmpty()) return false

        if (LINK_HOST_SUFFIXES.any { host.endsWith(it) }) return true

        return clipboardDomains.any { domain -> host == domain || host.endsWith(".$domain") }
    }
}
