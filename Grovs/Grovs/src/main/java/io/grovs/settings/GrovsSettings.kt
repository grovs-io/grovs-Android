package io.grovs.settings

import io.grovs.handlers.ConsentController
import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel

class GrovsSettings {
    /// Owns consent state; [sdkEnabled] is a view of it.
    @get:JvmSynthetic
    internal val consent: ConsentController = ConsentController()

    var debugLevel: LogLevel = LogLevel.ERROR
        set(value) {
            field = value
            DebugLogger.instance.logLevel = debugLevel
        }
    var useTestEnvironment: Boolean = false

    /// Consent gate. In memory only: every process starts from the value passed to configure().
    /// While false no new collection or request is admitted. Previously admitted local commits may finish.
    /// Reads the consent controller. Writing false revokes consent (every operation admitted so far
    /// stays invalid, even after a later enable); writing true starts a new consent generation.
    /// Writing the current value does nothing.
    var sdkEnabled: Boolean
        get() = consent.isEnabled
        set(value) {
            if (value) consent.enable() else consent.revoke()
        }
    var baseURL: String? = null

    /// Emit screen_view events automatically on Activity and Fragment resume.
    /// Defaults to true, matching the iOS SDK.
    var autoTrackScreenViews: Boolean = true

    /// Custom link hosts accepted by clipboard-assisted deferred deep linking, already normalized.
    var clipboardDomains: List<String> = emptyList()

}