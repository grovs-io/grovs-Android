package io.grovs.handlers

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.DeadObjectException
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import io.grovs.model.AppDetails
import io.grovs.model.CustomEventRules
import io.grovs.model.DebugLogger
import io.grovs.model.DeeplinkDetails
import io.grovs.model.GenerateLinkResponse
import io.grovs.model.LinkDetailsResponse
import io.grovs.model.LogLevel
import io.grovs.model.events.PaymentEvent
import io.grovs.model.events.PaymentEventType
import io.grovs.service.CustomRedirects
import io.grovs.service.GrovsService
import io.grovs.service.IGrovsService
import io.grovs.service.TrackingParams
import io.grovs.storage.ILocalCache
import io.grovs.storage.LocalCache
import io.grovs.utils.AppDetailsHelper
import io.grovs.utils.GVRetryResult
import io.grovs.utils.IAppDetailsHelper
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSResult
import io.grovs.utils.SystemClipboard
import io.grovs.utils.hasURISchemesConfigured
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
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
internal class GrovsManager(
    val context: Context,
    val application: Application,
    val grovsContext: GrovsContext,
    apiKey: String,
    grovsService: IGrovsService? = null,
    eventsManager: IEventsManager? = null,
    appDetailsHelper: IAppDetailsHelper? = null,
    customEventsManager: ICustomEventsManager? = null,
    activityProvider: ActivityProvider? = null,
    localCache: ILocalCache? = null,
    clipboardHandler: ClipboardHandler? = null,
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
    private val customEventsManager: ICustomEventsManager = customEventsManager
        ?: CustomEventsManager(context = context, grovsContext = grovsContext, grovsService = this.grovsService)
    private val appDetailsHelperForIntent: IAppDetailsHelper = appDetailsHelper ?: AppDetailsHelper(context)
    private val screenTracker: ScreenTracker = ScreenTracker(customEventsManager = this.customEventsManager)

    // Must be constructed before any launch event is logged: the handler arms itself on a zero opens counter.
    private val clipboardHandler: ClipboardHandler = clipboardHandler ?: ClipboardHandler(
        grovsService = this.grovsService,
        localCache = localCache ?: LocalCache(context = context),
        clipboard = SystemClipboard(context = context, activityProvider = activityProvider),
        clipboardDomains = grovsContext.settings.clipboardDomains,
    )

    /** One deadline covers install referrer, fingerprint lookup and clipboard fallback. */
    internal var attributionTimeoutMs: Long = 25_000
    internal var attributionScope: CoroutineScope =
        CoroutineScope(grovsContext.serialDispatcher + SupervisorJob())

    private sealed class AttributionWait {
        object NotStarted : AttributionWait()
        class Waiting(val deadline: Job) : AttributionWait()
        object Released : AttributionWait()
    }

    private var attributionWait: AttributionWait = AttributionWait.NotStarted

    /** Identity determines which lookup may commit; a new explicit link replaces the owner. */
    private class LinkResolution(val sessionId: String)
    private var activeResolution: LinkResolution? = null
    private val resolutionMutex = Mutex()

    private data class ResolvedDeeplink(val details: DeeplinkDetails, val eventLink: String?)

    /// Scope the attribute update runs on. Its dispatcher governs ordering, so tests can swap in a
    /// `TestScope` bound to `runTest`'s scheduler. SupervisorJob so one failed update can never
    /// cancel the scope for the next one.
    internal var attributesUpdateScope: CoroutineScope =
        CoroutineScope(grovsContext.serialDispatcher + SupervisorJob())

    private var lastIntentHandledReference: WeakReference<Intent>? = null
    private var handledIntentTokens: MutableList<Int> = mutableListOf()
    /// Stores if attributes needs to be updated after auth
    private var shouldUpdateAttributes = false

    /// The one outstanding attribute update. A newer one cancels it so the last write wins.
    /// Volatile because the public setters call in on whatever thread the host app uses; every
    /// mutation is additionally serialised by the monitor (see [updateAttributesIfNeeded]).
    @Volatile
    private var attributesUpdateJob: Job? = null

    /// Aliases awaiting a successful sync. Held while unauthenticated and across failed syncs.
    private var pendingScreenAliases: Map<String, String>? = null

    /// Bumped by every setScreenAliases, so an in-flight sync can tell whether its response is stale.
    private var aliasSyncGeneration: Int = 0
    @Volatile
    private var isClosed = false

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
        syncScreenAliasesIfNeeded()
    }

    fun onAppBackgrounded() {
        eventsManager.onAppBackgrounded()
    }

    fun setEnabled(enabled: Boolean) {
        DebugLogger.instance.log(LogLevel.INFO, "SDK setEnabled to: $enabled")
    }

    private fun ownsResolution(resolution: LinkResolution): Boolean =
        !isClosed && activeResolution === resolution

    private fun beginAttributionWait() {
        if (attributionWait !is AttributionWait.NotStarted) return
        eventsManager.beginLinkResolution()
        val deadline = attributionScope.launch {
            delay(attributionTimeoutMs)
            resolutionMutex.withLock {
                if (isClosed || attributionWait !is AttributionWait.Waiting) return@withLock
                // Do not cancel this deadline from inside its own coroutine: the flush suspends.
                attributionWait = AttributionWait.Released
                try {
                    DebugLogger.instance.log(LogLevel.INFO, "Link attribution timed out; releasing queued events")
                    eventsManager.completeLinkResolution(null, delayEvents = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLogger.instance.log(LogLevel.ERROR, "Link attribution release failed: ${e.message}")
                }
            }
        }
        attributionWait = AttributionWait.Waiting(deadline)
    }

    private fun releaseAttributionWait() {
        val waiting = attributionWait as? AttributionWait.Waiting ?: return
        attributionWait = AttributionWait.Released
        waiting.deadline.cancel()
        eventsManager.setEventsHeld(false)
    }

    /** Resolves a candidate without assigning it to events. Only the current owner may commit. */
    private suspend fun getDataForDevice(
        link: String?,
        resolution: LinkResolution,
    ): ResolvedDeeplink? {
        val request = appDetailsHelperForIntent.toAppDetails().copy(
            url = link,
            sessionId = resolution.sessionId,
        )
        val result = if (link == null) grovsService.payloadFor(request) else grovsService.payloadWithLinkFor(request)
        if (!ownsResolution(resolution)) return null

        return when (result) {
            is LSResult.Error -> {
                DebugLogger.instance.log(LogLevel.ERROR, "Error occurred while trying to resolve the deeplink. ${result.exception.message}")
                null
            }
            is LSResult.Success -> {
                if (result.data.link != null) {
                    clipboardHandler.markResolved()
                    ResolvedDeeplink(result.data, result.data.link)
                } else if (clipboardHandler.isPending) {
                    when (val outcome = clipboardHandler.runFlow(request) { ownsResolution(resolution) }) {
                        is ClipboardFlowOutcome.Matched -> ResolvedDeeplink(outcome.details, outcome.clipboardUrl)
                        else -> null
                    }
                } else {
                    result.data.takeIf { it.data != null }?.let { ResolvedDeeplink(it, null) }
                }
            }
        }
    }

    private suspend fun commitResolution(
        resolution: LinkResolution,
        result: ResolvedDeeplink?,
        delayEvents: Boolean,
    ) = resolutionMutex.withLock {
        if (!ownsResolution(resolution)) return@withLock
        val link = result?.details?.link
        customEventsManager.setLinkForFutureEvents(link)
        if (link != null) customEventsManager.attributePendingEvents(link, resolution.sessionId)
        if (!ownsResolution(resolution)) return@withLock

        (attributionWait as? AttributionWait.Waiting)?.deadline?.cancel()
        attributionWait = AttributionWait.Released
        eventsManager.completeLinkResolution(result?.eventLink, delayEvents = delayEvents)
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

                    if (clipboardHandler.isPending) {
                        resolutionMutex.withLock { if (!isClosed) beginAttributionWait() }
                    }
                    eventsManager.logAppLaunchEvents()

                    // Aliases set before the SDK was ready are held; send them now.
                    syncScreenAliasesIfNeeded()
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
                             copyToClipboardIos: Boolean? = null,
                             copyToClipboardAndroid: Boolean? = null,
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
            copyToClipboardIos = copyToClipboardIos,
            copyToClipboardAndroid = copyToClipboardAndroid,
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

    @Synchronized
    internal fun close() {
        if (isClosed) return
        isClosed = true
        activeResolution = null
        releaseAttributionWait()
        attributionScope.cancel()
        attributesUpdateJob?.cancel()
        attributesUpdateJob = null
        customEventsManager.close()
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

        if (isClosed) return null

        val repeatedIntent = intent.hashCode() == lastIntentHandledReference?.get()?.hashCode() ||
            (cacheIntent && handledIntentTokens.contains(intent.hashCode()))
        lastIntentHandledReference = WeakReference(intent)
        if (cacheIntent && !repeatedIntent) handledIntentTokens.add(intent.hashCode())

        val explicitLink = if (repeatedIntent) null else intent.data?.toString()
        val resolution = resolutionMutex.withLock {
            // Repeated lifecycle callbacks share the pending lookup; explicit links take precedence.
            if (activeResolution?.sessionId == grovsContext.sessionId && explicitLink == null) return@withLock null
            if (isClosed) return@withLock null
            LinkResolution(grovsContext.sessionId).also {
                activeResolution = it
                beginAttributionWait()
                customEventsManager.setLinkForFutureEvents(null)
            }
        } ?: return null

        try {
            val link = explicitLink ?: if (repeatedIntent) null else readInstallReferrer()
            if (!ownsResolution(resolution)) return null

            val result = getDataForDevice(link, resolution)
            if (!ownsResolution(resolution)) return null

            commitResolution(resolution, result, delayEvents)
            return if (ownsResolution(resolution)) result?.details else null
        } finally {
            withContext(NonCancellable) {
                resolutionMutex.withLock {
                    if (ownsResolution(resolution)) {
                        activeResolution = null
                        releaseAttributionWait()
                        attributionWait = AttributionWait.NotStarted
                    }
                }
            }
        }
    }

    private suspend fun readInstallReferrer(): String? = try {
        getInstallReferrer()
    } catch (e: SecurityException) {
        DebugLogger.instance.log(LogLevel.ERROR, "Security exception while trying to use install referrer.")
        null
    } catch (e: DeadObjectException) {
        DebugLogger.instance.log(LogLevel.ERROR, "Dead object exception while trying to use install referrer.")
        null
    }

    suspend fun logInAppPurchase(originalJson: String) {
        eventsManager.logInAppPurchase(originalJson = originalJson)
    }

    suspend fun track(name: String, properties: Map<String, Any>?, tags: List<String>?) {
        if (!CustomEventRules.isValidName(name)) {
            DebugLogger.instance.log(
                LogLevel.ERROR,
                "Cannot track \"$name\": the name is blank or reserved by the SDK. " +
                    "Reserved names: ${CustomEventRules.RESERVED_NAMES.joinToString()}"
            )
            return
        }
        customEventsManager.track(name = name, properties = properties, tags = tags)
    }

    suspend fun trackScreenView(screenName: String, properties: Map<String, Any>?) {
        screenTracker.trackScreen(rawName = screenName, properties = properties)
    }

    /**
     * Applies aliases to future screen views and syncs them to the backend. Safe to call before
     * authentication: the aliases are held and sent once the SDK is authenticated, and a failed sync
     * is retried on the next foreground.
     */
    suspend fun setScreenAliases(aliases: Map<String, String>) {
        screenTracker.setAliases(aliases)
        pendingScreenAliases = aliases
        aliasSyncGeneration++
        syncScreenAliasesIfNeeded()
    }

    /**
     * Sends the held aliases if any are pending and the SDK is authenticated. Keeps them on failure.
     * An empty map is a pending set like any other - it is how a caller clears the aliases the
     * backend already holds - so it is sent rather than skipped.
     */
    private suspend fun syncScreenAliasesIfNeeded() {
        if (authenticationState != AuthenticationState.AUTHENTICATED) return
        val pending = pendingScreenAliases ?: return

        val generation = aliasSyncGeneration
        when (grovsService.syncScreenAliases(pending)) {
            is LSResult.Success -> {
                // A newer setScreenAliases landed while this was in flight; that set owns the
                // pending state now and must not be cleared by this stale response.
                if (generation == aliasSyncGeneration) {
                    pendingScreenAliases = null
                }
            }
            is LSResult.Error -> {
                DebugLogger.instance.log(LogLevel.ERROR, "Failed to sync screen aliases, keeping them for retry.")
            }
        }
    }

    /**
     * Called from the Activity/Fragment lifecycle hooks. No-op when auto-tracking is disabled.
     * [screenClass] is the resolved screen's fully-qualified class name, used as the dedup identity
     * so distinct screens sharing a simpleName are not collapsed.
     */
    suspend fun autoTrackScreen(screenName: String, screenClass: String? = null) {
        if (!grovsContext.settings.autoTrackScreenViews) return
        screenTracker.trackScreen(rawName = screenName, properties = null, dedupKey = screenClass)
    }

    fun resetScreenDedup() {
        screenTracker.resetDedup()
    }

    fun setGlobalTags(tags: List<String>?) {
        customEventsManager.setGlobalTags(tags)
    }

    suspend fun flushCustomEvents() {
        customEventsManager.flush()
    }

    suspend fun logCustomPurchase(type: PaymentEventType, priceInCents: Int, currency: String, productId: String, startDate: InstantCompat? = InstantCompat.now()) {
        eventsManager.logCustomPurchase(type = type,
            priceInCents = priceInCents,
            currency = currency,
            productId = productId,
            startDate = startDate)
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

    /**
     * Synchronized because the `identifier` / `attributes` / `pushToken` setters run on whatever
     * thread the host app calls them from. Read-cancel-store has to be one atomic step: two threads
     * that both read the same old job would each cancel it and each store their own, leaving one
     * job unreferenced, uncancellable, and racing the other all the way to the backend.
     */
    @Synchronized
    private fun updateAttributesIfNeeded() {
        if (authenticationState != AuthenticationState.AUTHENTICATED) {
            shouldUpdateAttributes = true
            return
        }

        // A newer write supersedes whatever is in flight. The service retries forever, so two
        // concurrent updates race with no ordering and the stale one can land last.
        val superseded = attributesUpdateJob
        superseded?.cancel()
        attributesUpdateJob = attributesUpdateScope.launch {
            // Cancellation is a request, not an instant stop: the superseded call may still be
            // unwinding. Wait for it to actually finish before issuing ours, so the two never sit
            // in the backend's queue at once and land out of order.
            superseded?.join()

            // Read at launch time so the job always carries the newest values, not the ones the
            // setter happened to see.
            val identifier = identifier
            val attributes = attributes
            val pushToken = pushToken

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
