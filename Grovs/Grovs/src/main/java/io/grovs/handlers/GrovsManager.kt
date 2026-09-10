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
import kotlinx.coroutines.CompletableJob
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

    /// A link lookup in flight. `generation` is the number of links committed when it started; a
    /// later commit makes its result stale. `done` completes when the call exits, so fingerprint
    /// results can wait for a direct link the user opened (which takes precedence) to settle.
    private class Lookup(val generation: Int, val sessionId: String, val explicit: Boolean) {
        val done: CompletableJob = Job()
    }

    /// Every state below is guarded by [resolutionMutex]. [close] never touches it: it only flips
    /// [isClosed], which every lookup re-checks before committing.
    private val resolutionMutex = Mutex()
    private var committedLinks = 0
    /// The one fingerprint/referrer lookup per session; repeated onStart callbacks share it.
    private var sharedLookup: Lookup? = null
    private val explicitLookups = mutableListOf<Lookup>()
    /// Every lookup between creation and exit, including shared ones a newer session replaced.
    /// The hold stays armed while any of them can still commit.
    private val lookupsInFlight = mutableSetOf<Lookup>()
    /// Non-null while queued events are held for a pending lookup.
    private var holdDeadline: Job? = null

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
    @Volatile
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
        if (!grovsContext.settings.sdkEnabled) return
        eventsManager.onAppForegrounded()
        syncScreenAliasesIfNeeded()
    }

    fun onAppBackgrounded() {
        if (!grovsContext.settings.sdkEnabled) return
        eventsManager.onAppBackgrounded()
    }

    /// Consent granted on an already-authenticated SDK: send what was held while disabled.
    /// An unauthenticated SDK is instead re-authenticated by Grovs.setSDK, which records the launch.
    suspend fun onEnabled() {
        if (authenticationState != AuthenticationState.AUTHENTICATED) return
        if (shouldUpdateAttributes) updateAttributesIfNeeded()
        syncScreenAliasesIfNeeded()
        customEventsManager.flush()
        eventsManager.flush()
    }

    private fun isCurrent(lookup: Lookup): Boolean = !isClosed && committedLinks == lookup.generation

    /// Arms the events hold once; later lookups share the deadline already running.
    private fun armHold() {
        if (holdDeadline != null) return
        eventsManager.beginLinkResolution()
        customEventsManager.setEventsHeld(true)
        holdDeadline = attributionScope.launch {
            delay(attributionTimeoutMs)
            resolutionMutex.withLock {
                if (isClosed || holdDeadline == null) return@withLock
                holdDeadline = null
                // Nothing below may escape: this runs unattended on the SDK's own scope, so an
                // uncaught throw (events storage, disk) would reach the host app's default handler.
                try {
                    DebugLogger.instance.log(LogLevel.INFO, "Link attribution timed out; releasing queued events")
                    customEventsManager.setEventsHeld(false)
                    eventsManager.releaseLinkResolution(delayEvents = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLogger.instance.log(LogLevel.ERROR, "Link attribution release failed: ${e.message}")
                }
            }
        }
    }

    /// Releases the hold without changing attribution. Held events flush with whatever link they carry.
    private suspend fun releaseHold(delayEvents: Boolean) {
        val deadline = holdDeadline ?: return
        holdDeadline = null
        deadline.cancel()
        customEventsManager.setEventsHeld(false)
        eventsManager.releaseLinkResolution(delayEvents = delayEvents)
    }

    /// Resolves a candidate without committing it. Null when nothing resolved or the lookup went stale.
    private suspend fun getDataForDevice(link: String?, lookup: Lookup): ResolvedDeeplink? {
        val request = appDetailsHelperForIntent.toAppDetails().copy(
            url = link,
            sessionId = lookup.sessionId,
        )
        val result = if (link == null) grovsService.payloadFor(request) else grovsService.payloadWithLinkFor(request)
        if (!isCurrent(lookup)) return null

        return when (result) {
            is LSResult.Error -> {
                DebugLogger.instance.log(LogLevel.ERROR, "Error occurred while trying to resolve the deeplink. ${result.exception.message}")
                null
            }
            is LSResult.Success -> {
                if (result.data.link != null) {
                    ResolvedDeeplink(result.data, result.data.link)
                } else if (clipboardHandler.isPending) {
                    // Only an empty resolve on a fresh install runs the clipboard flow. A run already
                    // parked by another lookup keeps the hold; this call must not touch it.
                    when (val outcome = clipboardHandler.runFlow(request) { isCurrent(lookup) }) {
                        // INSTALL carries the clipboard string verbatim; the host gets the resolved details.
                        is ClipboardFlowOutcome.Matched -> ResolvedDeeplink(outcome.details, outcome.clipboardUrl)
                        else -> null
                    }
                } else {
                    result.data.takeIf { it.data != null }?.let { ResolvedDeeplink(it, null) }
                }
            }
        }
    }

    /// A direct link the user opened wins over anything inferred. Fingerprint and clipboard results
    /// wait for in-flight direct lookups; a committed one makes them stale, a rejected one lets them through.
    private suspend fun awaitExplicitLookups() {
        while (true) {
            val pending = resolutionMutex.withLock { explicitLookups.toList() }
            if (pending.isEmpty()) return
            pending.forEach { it.done.join() }
        }
    }

    /// Applies a resolved link everywhere and releases the hold. Only a still-current lookup commits;
    /// committing makes every other lookup in flight stale.
    private suspend fun commit(lookup: Lookup, result: ResolvedDeeplink, eventLink: String, delayEvents: Boolean): DeeplinkDetails? =
        resolutionMutex.withLock {
            if (!isCurrent(lookup)) return@withLock null
            committedLinks++
            // A resolved link from any path makes the clipboard flow moot.
            clipboardHandler.markResolved()
            val link = result.details.link
            // A late match attributes the session it lands in, the same one future events carry.
            // Attributing the session it was requested for would split one session's attribution.
            val sessionId = grovsContext.sessionId
            customEventsManager.setLinkForFutureEvents(link)
            if (link != null) customEventsManager.attributePendingEvents(link, sessionId)
            // Keep the hold closed while storage is updated: a background flush must not take
            // INSTALL between releasing the hold and applying the link.
            holdDeadline?.cancel()
            holdDeadline = null
            customEventsManager.setEventsHeld(false)
            eventsManager.completeLinkResolution(eventLink, delayEvents = delayEvents)
            result.details
        }

    suspend fun authenticate(): Boolean {
        // Consent gate. Authentication sends the device details and records the launch, so a
        // disabled SDK does neither. setSDK(true) authenticates from there.
        if (!grovsContext.settings.sdkEnabled) {
            DebugLogger.instance.log(LogLevel.INFO, "SDK disabled - not authenticating")
            return false
        }

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

        // Re-checked here: consent can be withdrawn while the device lookup was in flight, and this
        // is the last point before the authenticate request (and the launch it would record) goes out.
        if (!grovsContext.settings.sdkEnabled) {
            DebugLogger.instance.log(LogLevel.INFO, "SDK disabled - not authenticating")
            return false
        }

        DebugLogger.instance.log(LogLevel.INFO, "Device getting finished. Authenticating...")

        grovsService.authenticate(appDetails = appDetails).transformWhile {
            emit(it)
            it is GVRetryResult.Retrying
        }.collect { result ->
            when (result) {
                is GVRetryResult.Success -> {
                    // Consent withdrawn while the request was out: do not come up authenticated,
                    // do not record the launch. Re-enabling authenticates again.
                    if (!grovsContext.settings.sdkEnabled) {
                        DebugLogger.instance.log(LogLevel.INFO, "SDK disabled during authentication - discarding the response")
                        authenticationState = AuthenticationState.UNAUTHENTICATED
                        return@collect
                    }

                    authenticationState = AuthenticationState.AUTHENTICATED
                    grovsContext.grovsId = result.data.grovsId

                    // Update context attributes if needed
                    if (shouldUpdateAttributes) {
                        updateAttributesIfNeeded()
                    } else {
                        grovsContext.identifier = result.data.sdkIdentifier
                        grovsContext.attributes = result.data.sdkAttributes
                    }

                    // Non-cancellable: a setSDK(false) racing this suspension point must not cancel
                    // the job mid-write and leave the SDK AUTHENTICATED with no launch ever recorded.
                    // Storage-only, no flush, so running it to completion here is safe.
                    withContext(NonCancellable) { eventsManager.logAppLaunchEvents() }

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
        val lookup = resolutionMutex.withLock {
            if (isClosed) return@withLock null
            // Repeated lifecycle callbacks share the pending lookup; a direct link always runs.
            if (explicitLink == null && sharedLookup?.sessionId == grovsContext.sessionId) return@withLock null
            Lookup(committedLinks, grovsContext.sessionId, explicit = explicitLink != null).also {
                if (it.explicit) explicitLookups.add(it) else sharedLookup = it
                lookupsInFlight.add(it)
                armHold()
                if (it.explicit) {
                    // The user opened this link: events logged from now on carry it even if the
                    // lookup is cancelled. The backend's answer replaces it either way.
                    eventsManager.setLinkForFutureEvents(explicitLink)
                }
            }
        } ?: return null

        try {
            val link = explicitLink ?: if (repeatedIntent) null else readInstallReferrer()
            if (!isCurrent(lookup)) return null
            if (link != null && explicitLink == null) {
                // Consumed only by a lookup that actually sends it; a stale one leaves it for the next.
                lastReferrerUrl = link
            }

            val result = getDataForDevice(link, lookup)
            if (result == null) {
                if (lookup.explicit && isCurrent(lookup)) eventsManager.setLinkForFutureEvents(null)
                return null
            }
            val eventLink = result.eventLink ?: return result.details

            if (!lookup.explicit) awaitExplicitLookups()
            return commit(lookup, result, eventLink, delayEvents)
        } finally {
            withContext(NonCancellable) {
                resolutionMutex.withLock {
                    if (lookup.explicit) explicitLookups.remove(lookup) else if (sharedLookup === lookup) sharedLookup = null
                    lookupsInFlight.remove(lookup)
                    // The last lookup out with no link committed lets the queued events go as they are.
                    if (lookupsInFlight.isEmpty() && !isClosed) releaseHold(delayEvents)
                }
                lookup.done.complete()
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
        if (!grovsContext.settings.sdkEnabled) {
            DebugLogger.instance.log(LogLevel.ERROR, "The SDK is not enabled. Payment events cannot be sent.")
            return
        }
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
        if (authenticationState != AuthenticationState.AUTHENTICATED || !grovsContext.settings.sdkEnabled) return
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
        if (!grovsContext.settings.sdkEnabled) {
            DebugLogger.instance.log(LogLevel.ERROR, "The SDK is not enabled. Payment events cannot be sent.")
            return
        }
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
                                // Play caches the referrer, so only one not yet sent is reported.
                                // The caller records it once it actually sends it.
                                if (referrerUrl != lastReferrerUrl) {
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
                        else -> {
                            // FEATURE_NOT_SUPPORTED, SERVICE_UNAVAILABLE, DEVELOPER_ERROR, PERMISSION_ERROR:
                            // every outcome must resume, or the lookup that owns the hold never ends.
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
        if (authenticationState != AuthenticationState.AUTHENTICATED || !grovsContext.settings.sdkEnabled) {
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
