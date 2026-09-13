package io.grovs.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel

/**
 * Answers "does the phone have a network?" and reports when one comes back.
 *
 * Fails open: when Android is too old, refuses the callback, or throws, the SDK behaves as if it
 * were online and the retry timer alone brings it back. Nothing here can crash the host app.
 */
internal class NetworkMonitor(context: Context, private val onNetworkAvailable: () -> Unit) {
    private val connectivity =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * False only when Android reports no default network at all. Not a capabilities check on
     * purpose: "connected but no internet" should still try, and fail as retryable.
     */
    fun isOnline(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        // No connectivity service at all: nothing to ask, so fail open.
        val manager = connectivity ?: return true
        return try {
            manager.activeNetwork != null
        } catch (e: Exception) {
            true
        }
    }

    /** Registers one default-network callback. Android 7.0 (API 24) and up; does nothing below. */
    fun start() {
        if (callback != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val manager = connectivity ?: return
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            // Runs on a system thread. Hand off at once: the SDK's lifecycle work runs on main.
            override fun onAvailable(network: Network) {
                mainHandler.post { onNetworkAvailable() }
            }
        }
        try {
            manager.registerDefaultNetworkCallback(networkCallback)
            callback = networkCallback
        } catch (e: Exception) {
            // SecurityException when the host removed ACCESS_NETWORK_STATE or on Android 11 devices
            // with the platform bug; TooManyRequestsException past the per-app callback limit.
            DebugLogger.instance.log(LogLevel.INFO, "Network monitoring unavailable: ${e.message}")
        }
    }

    fun stop() {
        val registered = callback ?: return
        callback = null
        try {
            connectivity?.unregisterNetworkCallback(registered)
        } catch (e: Exception) {
            DebugLogger.instance.log(LogLevel.INFO, "Network monitoring stop failed: ${e.message}")
        }
    }
}
