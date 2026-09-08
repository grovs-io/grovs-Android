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
    var baseURL: String? = null

    /// Emit screen_view events automatically on Activity and Fragment resume.
    /// Defaults to true, matching the iOS SDK.
    var autoTrackScreenViews: Boolean = true

    /// Custom link hosts accepted by clipboard-assisted deferred deep linking, already normalized.
    var clipboardDomains: List<String> = emptyList()

}