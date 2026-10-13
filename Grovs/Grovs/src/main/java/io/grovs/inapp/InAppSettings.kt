package io.grovs.inapp

import android.util.Log
import io.grovs.GrovsInAppListener
import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel

/** What the host chose for in-app messages. Owned by Grovs, so it outlives a new configure(). Main thread. */
internal class InAppSettings {
    var listener: GrovsInAppListener? = null
    var paused: Boolean = false
    /** Lowercased screen names. */
    var excluded: Set<String> = emptySet()
    var debug: Boolean = false
    /** Where debug lines go; independent of the SDK log level, since the host asked for them. */
    var debugOutput: (String) -> Unit = { Log.i("GrovsInApp", it) }
}

internal object InAppLog {
    fun info(message: String) = DebugLogger.instance.log(LogLevel.INFO, message)
    fun error(message: String) = DebugLogger.instance.log(LogLevel.ERROR, message)
}
