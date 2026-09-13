package io.grovs.utils

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build

/**
 * Answers "does the phone have a network?".
 *
 * Fails open: when Android is too old or throws, the answer is "online", so a wrong answer can
 * never stop the SDK from trying. Nothing here can crash the host app.
 */
internal class NetworkMonitor(context: Context) {
    private val connectivity =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /**
     * False only when Android reports no default network at all. Not a capabilities check on
     * purpose: "connected but no internet" should still try, and fail as retryable.
     */
    fun isOnline(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            connectivity?.activeNetwork != null
        } catch (e: Exception) {
            true
        }
    }
}
