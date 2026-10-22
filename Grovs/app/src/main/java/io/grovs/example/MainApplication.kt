package io.grovs.example

import android.app.Application
import io.grovs.Grovs
import io.grovs.example.actions.LinkSource
import io.grovs.example.actions.LogState
import io.grovs.example.actions.LogType
import io.grovs.example.actions.callCode
import io.grovs.example.data.middleEllipsis
import io.grovs.example.data.toValueMap
import io.grovs.utils.flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        val store = AppGraph.store
        val profile = AppGraph.startedProfile

        Grovs.setInAppListener(LoggingInAppListener)
        Grovs.setInAppExcludedScreens(store.excludedScreens)
        Grovs.setInAppDebugLogging(store.inAppDebug)

        Grovs.configure(
            application = this,
            apiKey = profile.apiKey,
            useTestEnvironment = profile.useTestEnvironment,
            baseURL = profile.baseUrl,
            autoTrackScreenViews = profile.autoTrackScreens,
            clipboardDomains = profile.clipboardDomains.ifEmpty { null },
            enabled = profile.startEnabled,
        )
        Grovs.setDebug(store.debugLevel)

        val identity = store.identity
        Grovs.identifier = identity.identifier
        Grovs.attributes = identity.attributes.toValueMap()
        Grovs.setGlobalTags(store.globalTags.ifEmpty { null })
        Grovs.setScreenAliases(store.screenAliases.filter { it.key.isNotBlank() }.associate { it.key.trim() to it.raw })

        AppGraph.log.add(
            LogType.SDK, LogState.OK, "SDK configured",
            "${profile.name} · ${if (profile.useTestEnvironment) "test" else "prod"}",
            callCode(
                "configure",
                "apiKey" to middleEllipsis(profile.apiKey),
                "useTestEnvironment" to profile.useTestEnvironment,
                "baseURL" to profile.baseUrl,
                "autoTrackScreenViews" to profile.autoTrackScreens,
                "clipboardDomains" to profile.clipboardDomains,
                "enabled" to profile.startEnabled,
            ),
        )

        AppGraph.scope.launch {
            Grovs.Companion::openedLinkDetails.flow.filterNotNull().collect { details ->
                AppGraph.log.recordLinkOpen(details.link, details.data, details.tracking, LinkSource.FLOW)
            }
        }
    }
}
