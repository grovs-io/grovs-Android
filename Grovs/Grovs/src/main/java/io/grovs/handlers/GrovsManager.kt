package io.grovs.handlers

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.DeadObjectException
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import io.grovs.model.DebugLogger
import io.grovs.model.DeeplinkDetails
import io.grovs.model.GenerateLinkResponse
import io.grovs.model.LinkDetailsResponse
import io.grovs.model.LogLevel
import io.grovs.service.CustomRedirects
import io.grovs.service.GrovsService
import io.grovs.service.IGrovsService
import io.grovs.service.TrackingParams
import io.grovs.utils.AppDetailsHelper
import io.grovs.utils.GVRetryResult
import io.grovs.utils.IAppDetailsHelper
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSResult
import io.grovs.utils.hasURISchemesConfigured
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Serializable
import java.lang.ref.WeakReference
import java.net.URLDecoder
import kotlin.coroutines.resumeWithException

/**
 * GrovsManager is the core manager for the Grovs SDK.
 * 
 * This class can be configured with custom service and events manager implementations
 * for testing purposes. By default, it creates real implementations.
 * 
 * @param context The Android context
 * @param application The Android application instance
 * @param grovsContext The Grovs context containing SDK settings and state
 * @param apiKey The API key for authenticating with Grovs backend
 * @param grovsService Optional custom service implementation for testing (defaults to real service)
 * @param eventsManager Optional custom events manager for testing (defaults to real manager)
 * @param appDetailsHelper Optional custom app details helper for testing (defaults to real helper)
 */
class GrovsManager(
    val context: Context,
    val application: Application,
    val grovsContext: GrovsContext,
    apiKey: String,
    grovsService: IGrovsService? = null,
    eventsManager: IEventsManager? = null,
    appDetailsHelper: IAppDetailsHelper? = null
) {
    enum class AuthenticationState {
        UNAUTHENTICATED, RETRYING, AUTHENTICATED
    }

    companion object {
        private const val GROVS_PREFS_NAME = "grovs_prefs"
        private const val KEY_LAST_REFERRER = "last_referrer"
    }

    private val grovsService: IGrovsService = grovsService ?: GrovsService(context = context, apiKey = apiKey, grovsContext = grovsContext)
    private val appDetails: IAppDetailsHelper = appDetailsHelper ?: grovsContext.getAppDetails(context = context)
    private val eventsManager: IEventsManager = eventsManager ?: EventsManager(context = context, apiKey = apiKey, grovsContext = grovsContext)
    private val appDetailsHelperForIntent: IAppDetailsHelper = appDetailsHelper ?: AppDetailsHelper(context)

    private var lastIntentHandledReference: WeakReference<Intent>? = null
    private var handledIntentTokens: MutableList<Int> = mutableListOf()
    /// Stores if attributes needs to be updated after auth
    private var shouldUpdateAttributes = false

    /// A flag indicating whether the user is authenticated with the Grovs backend.
    var authenticationState: AuthenticationState = AuthenticationState.UNAUTHENTICATED

    private val prefs = context.getSharedPreferences(GROVS_PREFS_NAME, Context.MODE_PRIVATE)

    /// Last known install referrer value
    /// Lazily-loaded, in-memory cached value
    private var _lastReferrer: String? = null

    private var lastReferrerUrl: String?
        get() {
            if (_lastReferrer == null) {
                _lastReferrer = prefs.getString(KEY_LAST_REFERRER, null)
            }
            return _lastReferrer
        }
        set(value) {
            _lastReferrer = value
            prefs.edit().putString(KEY_LAST_REFERRER, value).apply()
        }

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

        val appDetails = appDetailsHelperForIntent.toAppDetails()
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
        grovsService.getDeviceFor(appDetails.deviceID).transformWhile {
            emit(it)
            it is GVRetryResult.Retrying
        }.collect { deviceResult ->
            when (deviceResult) {
                is GVRetryResult.Success -> {
                    grovsContext.lastSeen = deviceResult.data.lastSeen
                }
                is GVRetryResult.Retrying -> {
                    authenticationState = AuthenticationState.RETRYING
                    DebugLogger.instance.log(LogLevel.INFO, "Retrying to get the device.")
                }
                is GVRetryResult.Error -> {}
            }
        }

        DebugLogger.instance.log(LogLevel.INFO, "Device getting finished. Authenticating...")

        grovsService.authenticate(appDetails = appDetails).transformWhile {
            emit(it)
            it is GVRetryResult.Retrying
        }.collect { result ->
            when (result) {
                is GVRetryResult.Success -> {
                    authenticationState = AuthenticationState.AUTHENTICATED
                    grovsContext.grovsId = result.data.grovsId

                    // Update context attributes if needed
                    if (shouldUpdateAttributes) {
                        updateAttributesIfNeeded()
                    } else {
                        grovsContext.identifier = result.data.sdkIdentifier
                        grovsContext.attributes = result.data.sdkAttributes
                    }

                    eventsManager.logAppLaunchEvents()
                }
                is GVRetryResult.Retrying -> {
                    authenticationState = AuthenticationState.RETRYING
                    DebugLogger.instance.log(LogLevel.INFO, "Retrying authentication.")
                }
                is GVRetryResult.Error -> {
                    authenticationState = AuthenticationState.UNAUTHENTICATED
                    DebugLogger.instance.log(LogLevel.ERROR, "Failed to authenticate the app.")
                }
            }
        }

        return authenticationState == AuthenticationState.AUTHENTICATED
    }

    suspend fun generateLink(title: String?,
                             subtitle: String?,
                             imageURL: String?,
                             data: Map<String, Serializable>?,
                             tags: List<String>?,
                             customRedirects: CustomRedirects?,
                             showPreviewIos: Boolean?,
                             showPreviewAndroid: Boolean?,
                             tracking: TrackingParams?): LSResult<GenerateLinkResponse> {
        if (!grovsContext.settings.sdkEnabled) {
            DebugLogger.instance.log(LogLevel.ERROR, "The SDK is not enabled. Links cannot be generated.")
            return LSResult.Error(java.io.IOException("The SDK is not enabled. Links cannot be generated."))
        }
        if (authenticationState != AuthenticationState.AUTHENTICATED) {
            DebugLogger.instance.log(LogLevel.ERROR, "SDK is not ready for usage yet.")
            return LSResult.Error(java.io.IOException("SDK is not ready for usage yet."))
        }

        return grovsService.generateLink(title = title,
            subtitle = subtitle,
            imageURL = imageURL,
            data = data,
            tags = tags,
            customRedirects = customRedirects,
            showPreviewIos = showPreviewIos,
            showPreviewAndroid = showPreviewAndroid,
            tracking = tracking)
    }

    suspend fun linkDetails(path: String): LSResult<LinkDetailsResponse> {
        if (!grovsContext.settings.sdkEnabled) {
            DebugLogger.instance.log(LogLevel.ERROR, "The SDK is not enabled. Link details cannot be used.")
            return LSResult.Error(java.io.IOException("The SDK is not enabled. Link details cannot be used."))
        }
        if (authenticationState != AuthenticationState.AUTHENTICATED) {
            DebugLogger.instance.log(LogLevel.ERROR, "SDK is not ready for usage yet.")
            return LSResult.Error(java.io.IOException("SDK is not ready for usage yet."))
        }

        return grovsService.linkDetails(path = path)
    }

    fun start() {
        // Implementation for starting the GrovsManager, if needed.
    }

    suspend fun handleIntent(intent: Intent, delayEvents: Boolean, cacheIntent: Boolean = false): DeeplinkDetails? {
        if (!grovsContext.settings.sdkEnabled) {
            DebugLogger.instance.log(LogLevel.ERROR, "The SDK is not enabled. Links cannot be generated.")
            return null
        }
        if (authenticationState != AuthenticationState.AUTHENTICATED) {
            DebugLogger.instance.log(LogLevel.ERROR, "SDK is not ready for usage yet.")
            return null
        }

        // avoid handling same link multiple times (onStart gives same intent each time)
        if (intent.hashCode() == lastIntentHandledReference?.get()?.hashCode()) {
            DebugLogger.instance.log(LogLevel.INFO, " Avoid double handling assume, no link provided, trying to infer it.")
            return getDataForDevice(null, delayEvents = delayEvents)
        }
        lastIntentHandledReference = WeakReference(intent)

        if (cacheIntent) {
            if (handledIntentTokens.contains(intent.hashCode())) {
                DebugLogger.instance.log(LogLevel.INFO, "Intent already handled, ignoring it.")
                return getDataForDevice(null, delayEvents = delayEvents)
            } else {
                handledIntentTokens.add(intent.hashCode())
            }
        }

        intent.data?.toString()?.let { link ->
            return getDataForDevice(intent.data?.toString(), delayEvents = delayEvents)
        } ?: run {
            try {
                getInstallReferrer()?.let {
                    val result = getDataForDevice(it, delayEvents = delayEvents)
                    
                    return result
                }
            } catch (exception: SecurityException) {
                DebugLogger.instance.log(LogLevel.ERROR, "Security exception while trying to use install referrer.")
            } catch (exception: DeadObjectException) {
                DebugLogger.instance.log(LogLevel.ERROR, "Dead object exception while trying to use install referrer.")
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
                                // referrer gets cached by install referrer so we need to avoid handling it multiple times
                                if (referrerUrl != lastReferrerUrl) {
                                    lastReferrerUrl = referrerUrl

                                    continuation.resume(referrerUrl, null)
                                } else {
                                    continuation.resume(null, null)
                                }
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
        if (authenticationState != AuthenticationState.AUTHENTICATED) {
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