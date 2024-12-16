package io.grovs.settings

import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel

class GrovsSettings {
    var debugLevel: LogLevel = LogLevel.ERROR
        set(value) {
            field = value
            DebugLogger.instance.logLevel = debugLevel
        }
    var useTestEnvironment: Boolean = false
    var sdkEnabled: Boolean = true

}