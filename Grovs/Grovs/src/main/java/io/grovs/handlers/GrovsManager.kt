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

    /// Release valve: if the clipboard flow stalls this long, held events flush without a link.
    /// A match landing later still patches events the flush hasn't taken. Internal for tests.
    internal var clipboardFlowReleaseTimeoutMs: Long = 10_000

    /// Scope the release valve is launched on. Its dispatcher governs the delay, so tests can swap in
    /// a `TestScope` bound to `runTest`'s scheduler to make the valve respect virtual time.
    /// SupervisorJob so a failed valve can never cancel the scope for a later launch's valve.
    internal var clipboardValveScope: CoroutineScope =
        CoroutineScope(grovsContext.serialDispatcher + SupervisorJob())

    /// The one outstanding valve; re-entrant runs must not stack a second one.
    private var clipboardReleaseValve: Job? = null

    /// True from the call that starts a clipboard flow run until that same call's [ClipboardHandler.runFlow]
    /// returns. Independent from [clipboardReleaseValve] (which may already have fired and nulled itself
    /// while the run is still parked), so a re-entrant call never re-holds already-released events or
    /// arms a second valve.
    private var clipboardFlowRunning = false

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

    private suspend fun getDataForDevice(link: String? = null, delayEvents: Boolean): DeeplinkDetails? {
        eventsManager.setLinkToNewFutureActions(link, delayEvents = delayEvents)
        customEventsManager.setLinkForFutureEvents(link)

        val appDetails = appDetailsHelperForIntent.toAppDetails()
        appDetails.url = link
        // The backend mints the OPEN event for this call, so the session must ride along.
        appDetails.sessionId = grovsContext.sessionId
        val result = if (link == null) grovsService.payloadFor(appDetails) else grovsService.payloadWithLinkFor(appDetails)
        when (result) {
            is LSResult.Success -> {
                if (result.data.link != null) {
                    // A resolved link from any path makes the clipboard flow moot.
                    clipboardHandler.markResolved()
                } else if (clipboardHandler.isPending) {
                    // Only an empty resolve on a fresh install runs the clipboard flow.
                    return runClipboardFlow(appDetails, delayEvents = delayEvents)
                }

                eventsManager.setLinkToNewFutureActions(result.data.link, delayEvents = delayEvents)
                customEventsManager.setLinkForFutureEvents(result.data.link)
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

    /// INSTALL stays held (events hold gate) until the flow's terminal state or the release valve.
    private suspend fun runClipboardFlow(appDetails: AppDetails, delayEvents: Boolean): DeeplinkDetails? {
        if (clipboardFlowRunning) {
            // A run is already in flight - possibly still parked even after the valve already fired
            // and released the hold. It owns the hold and the valve; this call must touch neither.
            return null
        }
        clipboardFlowRunning = true

        eventsManager.setEventsHeld(true)

        if (clipboardReleaseValve == null) {
            // No explicit dispatcher here: this inherits clipboardValveScope's own dispatcher, which
            // is grovsContext.serialDispatcher in production and a TestScope's dispatcher in tests.
            clipboardReleaseValve = clipboardValveScope.launch {
                delay(clipboardFlowReleaseTimeoutMs)
                // Nothing below may escape: this runs unattended on the SDK's own scope, so an
                // uncaught throw (events storage, disk) would reach the host app's default handler.
                try {
                    DebugLogger.instance.log(LogLevel.ERROR, "Clipboard flow stalled - releasing held events without a link")
                    clipboardReleaseValve = null
                    eventsManager.setEventsHeld(false)
                    eventsManager.setLinkToNewFutureActions(null, delayEvents = delayEvents)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLogger.instance.log(LogLevel.ERROR, "Clipboard release valve failed: ${e.message}")
                }
            }
        }

        val outcome = try {
            clipboardHandler.runFlow(appDetails)
        } finally {
            clipboardFlowRunning = false
        }

        if (outcome is ClipboardFlowOutcome.AlreadyRunning) {
            // Unreachable in practice - reentry is gated above - but kept defensive: never touch the hold.
            return null
        }

        // The valve may already have fired (and nulled itself) while this call was parked.
        clipboardReleaseValve?.let {
            it.cancel()
            clipboardReleaseValve = null
            eventsManager.setEventsHeld(false)
        }

        return when (outcome) {
            is ClipboardFlowOutcome.Matched -> {
                // INSTALL carries the clipboard string verbatim; custom events get the resolved link.
                eventsManager.setLinkToNewFutureActions(outcome.clipboardUrl, delayEvents = delayEvents)
                customEventsManager.setLinkForFutureEvents(outcome.details.link)
                outcome.details
            }
            else -> {
                eventsManager.setLinkToNewFutureActions(null, delayEvents = delayEvents)
                customEventsManager.setLinkForFutureEvents(null)
                null
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
