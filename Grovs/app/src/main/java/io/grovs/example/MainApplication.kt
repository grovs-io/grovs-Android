package io.grovs.example

import android.app.Application
import io.grovs.Grovs
import io.grovs.example.actions.LinkSource
import io.grovs.example.actions.LogState
import io.grovs.example.actions.LogType
import io.grovs.example.actions.callCode
import io.grovs.example.data.Profile
import io.grovs.example.data.middleEllipsis
import io.grovs.example.data.toAliasMap
import io.grovs.example.data.toValueMap
import io.grovs.utils.flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        val store = AppGraph.store

        Grovs.setInAppListener(LoggingInAppListener)
        Grovs.setInAppExcludedScreens(store.excludedScreens)
        Grovs.setInAppDebugLogging(store.inAppDebug)

        val profile = try {
            configure(AppGraph.startedProfile)
        } catch (e: Exception) {
            val failed = AppGraph.startedProfile
            AppGraph.log.add(
                LogType.SDK, LogState.ERROR, "SDK configure failed",
                "${failed.name} · ${e.message ?: e}",
                "${configureCode(failed)}\n\n$e",
            )
            configure(AppGraph.fallBackToDefaultProfile())
        }
        Grovs.setDebug(store.debugLevel)

        val identity = store.identity
        Grovs.identifier = identity.identifier
        Grovs.attributes = identity.attributes.toValueMap()
        Grovs.setGlobalTags(store.globalTags.ifEmpty { null })
        Grovs.setScreenAliases(store.screenAliases.toAliasMap())

        AppGraph.log.add(
            LogType.SDK, LogState.OK, "SDK configured",
            "${profile.name} · ${if (profile.useTestEnvironment) "test" else "prod"}",
            configureCode(profile),
        )

        AppGraph.scope.launch {
            Grovs.Companion::openedLinkDetails.flow.filterNotNull().collect { details ->
                AppGraph.log.recordLinkOpen(details.link, details.data, details.tracking, LinkSource.FLOW)
            }
        }
    }

    private fun configure(profile: Profile): Profile {
        Grovs.configure(
            application = this,
            apiKey = profile.apiKey,
            useTestEnvironment = profile.useTestEnvironment,
            baseURL = profile.baseUrl,
            autoTrackScreenViews = profile.autoTrackScreens,
            clipboardDomains = profile.clipboardDomains.ifEmpty { null },
            enabled = profile.startEnabled,
        )
        return profile
    }

    private fun configureCode(profile: Profile): String = callCode(
        "configure",
        "apiKey" to middleEllipsis(profile.apiKey),
        "useTestEnvironment" to profile.useTestEnvironment,
        "baseURL" to profile.baseUrl,
        "autoTrackScreenViews" to profile.autoTrackScreens,
        "clipboardDomains" to profile.clipboardDomains,
        "enabled" to profile.startEnabled,
    )
}
