package io.grovs.utils

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.ViewTreeObserver
import android.view.textclassifier.TextClassifier
import io.grovs.handlers.ActivityProvider
import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * [IClipboard] backed by [ClipboardManager].
 *
 * On API 29+ the system only serves the clipboard to the app with window focus, so [awaitAccess]
 * must succeed before [describe] or [readText] can return anything.
 */
class SystemClipboard(
    private val context: Context,
    private val activityProvider: ActivityProvider?,
) : IClipboard {

    private val manager: ClipboardManager?
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    override suspend fun awaitAccess(timeoutMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true

        return withContext(Dispatchers.Main.immediate) {
            val activity = activityProvider?.requireActivity() ?: return@withContext false
            if (activity.hasWindowFocus()) return@withContext true

            val decorView = activity.window?.decorView ?: return@withContext false
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val observer = decorView.viewTreeObserver
                    val listener = object : ViewTreeObserver.OnWindowFocusChangeListener {
                        override fun onWindowFocusChanged(hasFocus: Boolean) {
                            if (hasFocus) {
                                observer.removeOnWindowFocusChangeListener(this)
                                if (continuation.isActive) continuation.resume(Unit)
                            }
                        }
                    }
                    observer.addOnWindowFocusChangeListener(listener)
                    continuation.invokeOnCancellation { observer.removeOnWindowFocusChangeListener(listener) }
                }
            } != null
        }
    }

    override suspend fun describe(): ClipDescriptionResult {
        return try {
            val manager = manager ?: return ClipDescriptionResult.INACCESSIBLE
            if (!manager.hasPrimaryClip()) return emptyOrInaccessible()

            val description = manager.primaryClipDescription ?: return emptyOrInaccessible()

            val isText = description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) ||
                description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)
            if (!isText) return ClipDescriptionResult.NOT_URL

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                description.classificationStatus == ClipDescription.CLASSIFICATION_COMPLETE &&
                description.getConfidenceScore(TextClassifier.TYPE_URL) == 0f
            ) {
                return ClipDescriptionResult.NOT_URL
            }

            ClipDescriptionResult.MAYBE_URL
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLogger.instance.log(LogLevel.INFO, "Clipboard describe failed: ${e.message}")
            ClipDescriptionResult.INACCESSIBLE
        }
    }

    /**
     * Disambiguates "the clipboard is empty" from "the system refused us".
     *
     * On API 29+ `hasPrimaryClip()` and `getPrimaryClipDescription()` are focus-gated exactly like
     * the read, so a false/null answer from a focus-less call is indistinguishable from an empty
     * clipboard by itself. Only a window that actually has focus proves the clipboard is empty;
     * anything else (including no activity to ask, matching [awaitAccess]'s "no activity -> false")
     * is INACCESSIBLE, so the flow retries instead of clearing the pending flag for good.
     */
    private suspend fun emptyOrInaccessible(): ClipDescriptionResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ClipDescriptionResult.NO_CONTENT

        val hasFocus = withContext(Dispatchers.Main.immediate) {
            activityProvider?.requireActivity()?.hasWindowFocus() ?: false
        }
        return if (hasFocus) ClipDescriptionResult.NO_CONTENT else ClipDescriptionResult.INACCESSIBLE
    }

    override fun readText(): String? {
        return try {
            val item = manager?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
            item.text?.toString()?.takeIf { it.isNotEmpty() } ?: item.uri?.toString()
        } catch (e: Exception) {
            DebugLogger.instance.log(LogLevel.INFO, "Clipboard read failed: ${e.message}")
            null
        }
    }

    override fun clear() {
        try {
            val manager = manager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.clearPrimaryClip()
            } else {
                manager.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (e: Exception) {
            DebugLogger.instance.log(LogLevel.INFO, "Clipboard clear failed: ${e.message}")
        }
    }
}
