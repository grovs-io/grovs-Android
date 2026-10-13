package io.grovs.inapp

import android.os.Handler
import android.os.Looper

/** Runs work on the main looper after a delay, until cancelled. */
internal object InAppMainTimer {
    fun after(delayMillis: Long, work: () -> Unit): InAppCancellable =
        after(delayMillis, Handler(Looper.getMainLooper()), work)

    fun after(delayMillis: Long, handler: Handler, work: () -> Unit): InAppCancellable {
        val runnable = Runnable { work() }
        handler.postDelayed(runnable, delayMillis)
        return object : InAppCancellable {
            override fun cancel() = handler.removeCallbacks(runnable)
        }
    }
}
