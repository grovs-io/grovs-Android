package io.grovs.handlers

import android.app.Application
import android.content.Context
import android.content.Intent
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import io.grovs.model.DebugLogger
import io.grovs.model.DeeplinkDetails
import io.grovs.model.GenerateLinkResponse
import io.grovs.model.LogLevel
import io.grovs.service.GrovsService
import io.grovs.utils.AppDetailsHelper
import io.grovs.utils.LSResult
import io.grovs.utils.hasURISchemesConfigured
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Serializable
import java.lang.ref.WeakReference
import java.net.URLDecoder
import kotlin.coroutines.resumeWithException

class GrovsManager(val context: Context, val application: Application, val grovsContext: GrovsContext, apiKey: String) {
    private val grovsService = GrovsService(context = context, apiKey = apiKey, grovsContext = grovsContext)
    private val appDetails = grovsContext.getAppDetails(context = context)
    private val eventsManager = EventsManager(context = context, apiKey = apiKey, grovsContext = grovsContext)

    private var lastItentHandledReference: WeakReference<Intent>? = null
    /// Stores if attributes needs to be updated after auth
    private var shouldUpdateAttributes = false

    /// A flag indicating whether the user is authenticated with the Grovs backend.
    var authenticated = false

    var identifier: String?
        get() = grovsContext.identifier
        set(value) {
            grovsContext.identifier = value
            updateAttributesIfNeeded()
        }

    var pushToken: String?
        get() = grovsContext.pushToken
        set(value) {
            grovsContext.pushToken = value
            updateAttributesIfNeeded()
        }


    var attributes: Map<String, Any>?
        get() = grovsContext.attributes
        set(value) {
            grovsContext.attributes = value
            updateAttributesIfNeeded()
        }

    suspend fun onAppForegrounded() {
        eventsManager.onAppForegrounded()
    }

    fun onAppBackgrounded() {
        eventsManager.onAppBackgrounded()
    }

    fun setEnabled(enabled: Boolean) {
        DebugLogger.instance.log(LogLevel.INFO, "SDK setEnabled to: $enabled")
    }

    private suspend fun getDataForDevice(link: String? = null, delayEvents: Boolean): DeeplinkDetails? {
        eventsManager.setLinkToNewFutureActions(link, delayEvents = delayEvents)

        val appDetails = AppDetailsHelper(context).toAppDetails()
        appDetails.url = link
        val result = if (link == null) grovsService.payloadFor(appDetails) else grovsService.payloadWithLinkFor(appDetails)
        when (result) {
            is LSResult.Success -> {
                eventsManager.setLinkToNewFutureActions(result.data.link, delayEvents = delayEvents)
                // if link and data are null we consider we have no deeplink
                if ((result.data.data == null) && (result.data.link == null)) {
                    return null
                } else {
                    return result.data
                }
            }
            is LSResult.Error -> {
                DebugLogger.instance.log(LogLevel.ERROR, "Error occurred while trying to resolve the deeplink. ${result.exception.message}")
                return null
            }
        }
    }

    suspend fun authenticate(): Boolean {
        if (!context.hasURISchemesConfigured()) {
            DebugLogger.instance.log(LogLevel.INFO, "URI schemes are not configured. Deep linking won't work!")
            return false
        }

        val appDetails = appDetails.toAppDetails()

        val deviceResult = grovsService.getDeviceFor(appDetails.deviceID)
        when (deviceResult) {
            is LSResult.Success -> {
                grovsContext.lastSeen = deviceResult.data.lastSeen
            }
            is LSResult.Error -> {}
        }

        val result = grovsService.authenticate(appDetails = appDetails)
        when (result) {
            is LSResult.Success -> {
                authenticated = true
                grovsContext.grovsId = result.data.grovsId

                // Update context attributes if needed
                if (shouldUpdateAttributes) {
                    updateAttributesIfNeeded()
                } else {
                    grovsContext.identifier = result.data.sdkIdentifier
                    grovsContext.attributes = result.data.sdkAttributes
                }

                eventsManager.logAppLaunchEvents()

                return true
            }
            is LSResult.Error -> {
                authenticated = true
                DebugLogger.instance.log(LogLevel.ERROR, "Failed to authenticate the app.")

                return false
            }
        }
    }

    suspend fun generateLink(title: String?, subtitle: String?, imageURL: String?, data: Map<String, Serializable>?, tags: List<String>?): LSResult<GenerateLinkResponse> {
        if (!grovsContext.settings.sdkEnabled) {
            DebugLogger.instance.log(LogLevel.ERROR, "The SDK is not enabled. Links cannot be generated.")
            return LSResult.Error(java.io.IOException("The SDK is not enabled. Links cannot be generated."))
        }
        if (!authenticated) {
            DebugLogger.instance.log(LogLevel.ERROR, "SDK is not ready for usage yet.")
            return LSResult.Error(java.io.IOException("SDK is not ready for usage yet."))
        }

        return grovsService.generateLink(title = title,
            subtitle = subtitle,
            imageURL = imageURL,
            data = data,
            tags = tags)
    }

    fun start() {
        // Implementation for starting the GrovsManager, if needed.
    }

    suspend fun handleIntent(intent: Intent, delayEvents: Boolean): DeeplinkDetails? {
        if (!grovsContext.settings.sdkEnabled) {
            DebugLogger.instance.log(LogLevel.ERROR, "The SDK is not enabled. Links cannot be generated.")
            return null
        }
        if (!authenticated) {
            DebugLogger.instance.log(LogLevel.ERROR, "SDK is not ready for usage yet.")
            return null
        }

        // avoid handling same link multiple times
        if (intent === lastItentHandledReference?.get()) {
            DebugLogger.instance.log(LogLevel.INFO, "No link provided, trying to infer it.")
            return getDataForDevice(null, delayEvents = delayEvents)
        }
        lastItentHandledReference = WeakReference(intent)

        intent.data?.toString()?.let { link ->
            return getDataForDevice(intent.data?.toString(), delayEvents = delayEvents)
        } ?: run {
            getInstallReferrer()?.let {
                return getDataForDevice(it, delayEvents = delayEvents)
            }

            return getDataForDevice(null, delayEvents = delayEvents)
        }
    }

    private suspend fun getInstallReferrer(): String? {
        return suspendCancellableCoroutine { continuation ->
            DebugLogger.instance.log(LogLevel.INFO, "Checking InstallReferrer")
            val referrerClient = InstallReferrerClient.newBuilder(context).build()

            referrerClient.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    DebugLogger.instance.log(LogLevel.INFO, "Got response from InstallReferrer: $responseCode")
                    when (responseCode) {
                        InstallReferrerClient.InstallReferrerResponse.OK -> {
                            try {
                                val referrerDetails = referrerClient.installReferrer
                                val referrerUrl = referrerDetails.installReferrer
                                DebugLogger.instance.log(LogLevel.INFO, "Got url from InstallReferrer: $referrerUrl")

                                continuation.resume(referrerUrl, null)
                            } catch (e: Exception) {
                                continuation.resumeWithException(e)
                            } finally {
                                referrerClient.endConnection()
                            }
                        }
                        InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED,
                        InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE -> {
                            continuation.resume(null, null)
                            referrerClient.endConnection()
                        }
                    }
                }

                override fun onInstallReferrerServiceDisconnected() {
                    DebugLogger.instance.log(LogLevel.INFO, "InstallReferrer disconnected")
                    if (continuation.isActive) {
                        continuation.resume(null, null)
                    }
                }
            })

            // Ensure that if the coroutine is cancelled, the connection is ended
            continuation.invokeOnCancellation {
                referrerClient.endConnection()
            }
        }
    }

    private fun updateAttributesIfNeeded() {
        if (!authenticated) {
            shouldUpdateAttributes = true
            return
        }

        GlobalScope.launch {
            val result = grovsService.updateAttributes(identifier = identifier, attributes = attributes, pushToken = pushToken)
            when (result) {
                is LSResult.Success -> {
                    shouldUpdateAttributes = false
                }
                is LSResult.Error -> {}
            }
        }
    }
}