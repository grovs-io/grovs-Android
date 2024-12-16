package io.grovs.model

import android.util.Log

public enum class LogLevel {
    INFO, ERROR
}

class DebugLogger {
    companion object {
        val instance = DebugLogger()
    }

    var logLevel: LogLevel = LogLevel.ERROR

    fun log(level: LogLevel, message: String) {
        if (logLevel == LogLevel.ERROR && level == LogLevel.INFO) {
            return
        }

        // Use Throwable to get the current stack trace
        val stackTrace = Throwable().stackTrace
        // stackTrace[1] is the calling method (current method's caller)
        val element = stackTrace[1]
        val fileName = element.fileName
        val functionName = element.methodName
        val lineNumber = element.lineNumber

        var logMessage = "\uD83D\uDD17GROVS [${level.name}] $fileName -> $functionName [Line $lineNumber]: $message"

        if (level == LogLevel.ERROR) {
            logMessage = "\n\n\n$logMessage\n\n\n"
        }

        // Log the message using Android's Log class
        when (level) {
            LogLevel.INFO -> Log.d("Logger", logMessage)
            LogLevel.ERROR -> Log.e("Logger", logMessage)
        }
    }

}