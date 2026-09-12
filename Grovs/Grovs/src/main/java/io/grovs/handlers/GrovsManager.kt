package io.grovs.handlers

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.DeadObjectException
import android.os.SystemClock
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
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
    /// Where the attribute sync runs. Defaults to the configuration's scope; tests pass one they drive.
    attributesSyncScope: CoroutineScope? = null,
) {
    enum class AuthenticationState {
        UNAUTHENTICATED, RETRYING, AUTHENTICATED
    }

    companion object {
        private const val GROVS_PREFS_NAME = "grovs_prefs"
        private const val KEY_LAST_REFERRER = "last_referrer"

        /// First backoff window after a failed authentication; doubles per consecutive failure.
        private const val AUTHENTICATION_BACKOFF_BASE_MS = 10_000L
        private const val AUTHENTICATION_BACKOFF_MAX_MS = 300_000L
        /// Keeps the doubling from overflowing once the ceiling is reached anyway.
        private const val AUTHENTICATION_BACKOFF_MAX_SHIFT = 16
    }

    internal val configuration: ConsentConfiguration = grovsContext.consent.currentConfiguration

    private val grovsService: IGrovsService = grovsService ?: GrovsService(context = context, apiKey = apiKey, grovsContext = grovsContext)
    private val appDetails: IAppDetailsHelper by lazy { appDetailsHelper ?: grovsContext.getAppDetails(context = context) }
    private val eventsManager: IEventsManager = eventsManager ?: EventsManager(context = context, apiKey = apiKey, grovsContext = grovsContext)
    private val customEventsManager: ICustomEventsManager = customEventsManager
        ?: CustomEventsManager(context = context, grovsContext = grovsContext, grovsService = this.grovsService)
    private val appDetailsHelperForIntent: IAppDetailsHelper by lazy { appDetailsHelper ?: AppDetailsHelper(context) }
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
    private class Lookup(val generation: Int, val sessionId: String, val explicit: Boolean, val token: ConsentToken) {
        val done: CompletableJob = Job()
        var committed = false
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
    private var holdToken: ConsentToken? = null

    private data class ResolvedDeeplink(val details: DeeplinkDetails, val eventLink: String?)

    private var lastIntentHandledReference: WeakReference<Intent>? = null
    private var handledIntentTokens: MutableList<Int> = mutableListOf()

    /// Aliases awaiting a successful sync. Held while unauthenticated and across failed syncs.
    private var pendingScreenAliases: Map<String, String>? = null

    /// Bumped by every setScreenAliases, so an in-flight sync can tell whether its response is stale.
    private var aliasSyncGeneration: Int = 0
    @Volatile
    private var isClosed = false

    /// A flag indicating whether the user is authenticated with the Grovs backend.
    @Volatile
    var authenticationState: AuthenticationState = AuthenticationState.UNAUTHENTICATED

    /// Consecutive failed authentication attempts. Widens the window below.
    private var authenticationFailureCount = 0

    /// Elapsed-time source the window is measured against. A seam so tests can move time.
    internal var authenticationBackoffElapsedMs: () -> Long = { SystemClock.elapsedRealtime() }

    /// When the next authentication attempt is allowed. Null means "now".
    private var authenticationRetryAt: Long? = null

    /**
     * Whether a foreground may spend a request on authentication. A bad API key fails on every
     * attempt and each one costs a request plus a user agent fetch, so repeated failures widen the
     * window to a 300s ceiling.
     */
    internal fun canAttemptAuthentication(): Boolean {
        val retryAt = authenticationRetryAt ?: return true
        return authenticationBackoffElapsedMs() >= retryAt
    }

    internal fun recordAuthenticationOutcome(success: Boolean) {
        if (success) {
            authenticationFailureCount = 0
            authenticationRetryAt = null
            return
        }
        authenticationFailureCount++
        val shift = (authenticationFailureCount - 1).coerceAtMost(AUTHENTICATION_BACKOFF_MAX_SHIFT)
        val delay = minOf(AUTHENTICATION_BACKOFF_BASE_MS shl shift, AUTHENTICATION_BACKOFF_MAX_MS)
        authenticationRetryAt = authenticationBackoffElapsedMs() + delay
    }

    /// The user attributes the backend last confirmed, or that were adopted from it at
    /// authentication. The attribute sync sends whenever [GrovsContext.userAttributes] differs.
    @Volatile
    private var acknowledgedAttributes: UserAttributes = grovsContext.userAttributes.value

    /// Bumped to re-offer an unacknowledged value once sending can succeed again: after
    /// authentication and on every new consent grant.
    private val attributesResyncRequests = MutableStateFlow(0)

    /// The one long-lived attribute sync. By default it runs in the configuration's scope, so
    /// retiring the configuration stops it as well as [close].
    private val attributesSync: Job = launchAttributesSync(
        attributesSyncScope ?: CoroutineScope(configuration.scope.coroutineContext + grovsContext.serialDispatcher)
    )

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

    // Views onto the desired state. The attribute sync observes every write, from any thread.
    var identifier: String? by grovsContext::identifier
    var pushToken: String? by grovsContext::pushToken
    var attributes: Map<String, Any>? by grovsContext::attributes

    suspend fun onAppForegrounded() {
        grovsContext.isForeground = true
        if (grovsContext.consent.workToken(configuration) == null) return
        // A value the backend never acknowledged stays pending forever otherwise: the sync only
        // reacts to changes, and a failed send is not a change.
        if (grovsContext.userAttributes.value != acknowledgedAttributes) resyncAttributes()
        eventsManager.onAppForegrounded()
        syncScreenAliasesIfNeeded()
    }

    fun onAppBackgrounded() {
        grovsContext.isForeground = false
        // Custom-event campaigns last the whole session, brief backgrounds included; only the
        // system-event link ends here.
        if (grovsContext.consent.tryAcquire(configuration) == null) {
            // Not admitted: no storage writes, but the committed link still ends with the session,
            // exactly as when enabled, so it cannot leak into the next session's events.
            eventsManager.setLinkForFutureEvents(null)
            return
        }
        eventsManager.onAppBackgrounded()
    }

    /** Called under the storage permit reserved at revocation, after accepted launch writes finish. */
    internal suspend fun onDisabled(timestamp: InstantCompat) {
        (eventsManager as? EventsManager)?.closeEngagementAt(timestamp)
    }

    /** Resume permitted state and queues; never replay a previous intent. */
    suspend fun onEnabled() {
        if (grovsContext.consent.workToken(configuration) == null) return
        if (authenticationState != AuthenticationState.AUTHENTICATED) return
        resyncAttributes()
        syncScreenAliasesIfNeeded()
        if (grovsContext.isForeground) (eventsManager as? EventsManager)?.resumeEngagement()
        customEventsManager.flush()
        eventsManager.flush()
    }

    private fun isCurrent(lookup: Lookup): Boolean = !isClosed && committedLinks == lookup.generation && grovsContext.consent.isCurrent(lookup.token)

    private fun ownsHold(token: ConsentToken): Boolean = holdToken?.let {
        it.configuration === token.configuration && it.generation == token.generation
    } == true

    /** Lookups share a deadline only within the same consent generation. */
    private fun armHold(token: ConsentToken) {
        if (holdDeadline != null && ownsHold(token)) return
        holdDeadline?.cancel()
        holdToken = token
        eventsManager.beginLinkResolution()
        customEventsManager.setEventsHeld(true)
        holdDeadline = grovsContext.consent.launchOperation(token, scope = attributionScope) {
            delay(attributionTimeoutMs)
            resolutionMutex.withLock {
                if (isClosed || holdDeadline == null || !ownsHold(token) || !grovsContext.consent.isCurrent(token)) return@withLock
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
    private suspend fun releaseHold(token: ConsentToken, delayEvents: Boolean) {
        if (!ownsHold(token)) return
        val deadline = holdDeadline ?: return
        holdDeadline = null
        deadline.cancel()
        customEventsManager.setEventsHeld(false)
        eventsManager.setEventsHeld(false)
        if (grovsContext.consent.isCurrent(token)) eventsManager.releaseLinkResolution(delayEvents = delayEvents)
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
                    // The barrier the flow re-checks at every suspension point covers consent as
                    // well as staleness, so a revocation between the status call, the focus wait,
                    // the description, the read and the match stops it before the next of those -
                    // and no later grant lets this run continue.
                    val token = grovsContext.consent.workToken(configuration)
                    val stillAllowed = { isCurrent(lookup) && token != null && grovsContext.consent.isCurrent(token) }
                    var outcome = clipboardHandler.runFlow(request, stillAllowed)
                    // A startup dialog can outlive one focus wait without another onStart.
                    // Retry only this condition, under the original consent token and hold deadline.
                    while (outcome == ClipboardFlowOutcome.AwaitingFocus &&
                        holdDeadline?.isActive == true && stillAllowed()
                    ) {
                        delay(250)
                        if (holdDeadline?.isActive != true || !stillAllowed()) break
                        outcome = clipboardHandler.runFlow(request, stillAllowed)
                    }
                    when (outcome) {
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
            val pending = resolutionMutex.withLock { explicitLookups.filter(::isCurrent) }
            if (pending.isEmpty()) return
            pending.forEach { it.done.join() }
        }
    }

    /// Applies a resolved link everywhere and releases the hold. Only a still-current lookup commits;
    /// committing makes every other lookup in flight stale.
    private suspend fun commit(lookup: Lookup, result: ResolvedDeeplink, eventLink: String, delayEvents: Boolean): DeeplinkDetails? =
        resolutionMutex.withLock {
            if (!isCurrent(lookup)) return@withLock null
            // A result resolved under consent that has since gone away is not applied: it would set
            // future attribution, backfill stored events and mark the clipboard flow resolved, all
            // of which are exactly the effects the revocation was meant to stop.
            val token = grovsContext.consent.workToken(configuration)
            if (token == null) {
                DebugLogger.instance.log(LogLevel.INFO, "SDK consent withdrawn - not committing the resolved link")
                return@withLock null
            }
            val permit = grovsContext.consent.tryAdmitCommit(token, CommitKind.STORAGE_TRANSACTION)
                ?: return@withLock null
            permit.finish {
                committedLinks++
                lookup.committed = true
                clipboardHandler.markResolved()
                val link = result.details.link
                // Future events and backfill belong to the same session captured at commit time.
                val sessionId = grovsContext.sessionId
                customEventsManager.setLinkForFutureEvents(link, sessionId)
                if (link != null) customEventsManager.attributePendingEvents(link, sessionId)
                eventsManager.completeLinkResolution(eventLink, delayEvents = delayEvents)
            }
            if (ownsHold(token)) {
                holdDeadline?.cancel()
                holdDeadline = null
                customEventsManager.setEventsHeld(false)
                eventsManager.setEventsHeld(false)
                // Queued, not awaited: this runs under the resolution lock and must not wait on the network.
                if (grovsContext.consent.isCurrent(token)) eventsManager.requestFlush()
            }
            result.details
        }

    /**
     * Authenticates the device under a consent operation. Returns false without sending anything
     * when consent is not granted, and false when consent is withdrawn before the response is
     * committed. A revoked attempt never resumes; re-enabling authenticates again under a new token.
     */
    suspend fun authenticate(): Boolean = try {
        grovsContext.consent.runOperation(configuration) { authenticateUnderConsent() }
    } catch (e: ConsentRevokedException) {
        DebugLogger.instance.log(LogLevel.INFO, "SDK consent (${e.reason}) - not authenticating")
        false
    }

    private suspend fun authenticateUnderConsent(): Boolean {
        val consent = grovsContext.consent
        // Present because runOperation established it; every check below is against this one token,
        // never against a token acquired later, so a disable-enable cycle cannot revive this call.
        val token = currentConsentToken() ?: return false

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
                    if (consent.isCurrent(token)) grovsContext.lastSeen = deviceResult.data.lastSeen
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
        if (!consent.isCurrent(token)) {
            DebugLogger.instance.log(LogLevel.INFO, "SDK consent withdrawn during the device lookup - not authenticating")
            return false
        }

        DebugLogger.instance.log(LogLevel.INFO, "Device getting finished. Authenticating...")

        grovsService.authenticate(appDetails = appDetails).transformWhile {
            emit(it)
            it is GVRetryResult.Retrying
        }.collect { result ->
            when (result) {
                is GVRetryResult.Success -> {
                    // The response is only accepted if the commit is admitted while the token is
                    // still current. Losing that race means: do not come up authenticated, do not
                    // record the launch. Re-enabling authenticates again under a new token.
                    val permit = consent.tryAdmitCommit(token, CommitKind.AUTHENTICATION)
                    if (permit == null) {
                        DebugLogger.instance.log(LogLevel.INFO, "SDK consent withdrawn during authentication - discarding the response")
                        authenticationState = AuthenticationState.UNAUTHENTICATED
                        return@collect
                    }

                    // Admitted before any revocation, so this local bookkeeping runs to completion:
                    // the launch record, the opens counters and the AUTHENTICATED state become
                    // visible together. A revocation racing this waits for the permit to close, so
                    // the next grant resumes after it rather than recording a second launch.
                    permit.finish {
                        grovsContext.markAuthenticated(result.data.grovsId, configuration)
                        adoptBackendAttributes(result.data.sdkIdentifier, result.data.sdkAttributes)
                        clipboardHandler.armIfNeeded()
                        eventsManager.logAppLaunchEvents()
                        authenticationState = AuthenticationState.AUTHENTICATED
                        recordAuthenticationOutcome(true)
                    }
                    if (!consent.isCurrent(token)) return@collect
                    // Values set while unauthenticated can go out now. The send is network work, so
                    // the attribute sync runs it outside the commit, cancellable and gated like any other.
                    resyncAttributes()

                    // Aliases set before the SDK was ready are held; send them now.
                    syncScreenAliasesIfNeeded()
                }
                is GVRetryResult.Retrying -> {
                    authenticationState = AuthenticationState.RETRYING
                    DebugLogger.instance.log(LogLevel.INFO, "Retrying authentication.")
                }
                is GVRetryResult.Error -> {
                    authenticationState = AuthenticationState.UNAUTHENTICATED
                    recordAuthenticationOutcome(false)
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
        if (grovsContext.consent.workToken(configuration) == null) {
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
        if (grovsContext.consent.workToken(configuration) == null) {
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
        attributesSync.cancel()
        customEventsManager.close()
    }

    suspend fun handleIntent(intent: Intent, delayEvents: Boolean, cacheIntent: Boolean = false): DeeplinkDetails? {
        val token = grovsContext.consent.workToken(configuration) ?: return null
        return try {
            grovsContext.consent.runOperation(token) { handleIntentUnderConsent(intent, delayEvents, cacheIntent, token) }
        } catch (_: ConsentRevokedException) {
            null
        }
    }

    private suspend fun handleIntentUnderConsent(intent: Intent, delayEvents: Boolean, cacheIntent: Boolean, token: ConsentToken): DeeplinkDetails? {
        // Consent was admitted by handleIntent and is current here: runOperation checks [token]
        // before this body runs.
        if (authenticationState != AuthenticationState.AUTHENTICATED) {
            DebugLogger.instance.log(LogLevel.ERROR, "SDK is not ready for usage yet.")
            return null
        }

        if (isClosed) return null
        if (!grovsContext.consent.storeIfConsented(configuration) { clipboardHandler.armIfNeeded() }) return null

        var repeatedIntent = false
        var newlyCachedIntent = false
        var explicitLink: String? = null
        val lookup = resolutionMutex.withLock {
            if (isClosed || !grovsContext.consent.isCurrent(token)) return@withLock null
            repeatedIntent = intent.hashCode() == lastIntentHandledReference?.get()?.hashCode() ||
                (cacheIntent && handledIntentTokens.contains(intent.hashCode()))
            lastIntentHandledReference = WeakReference(intent)
            newlyCachedIntent = cacheIntent && !repeatedIntent
            if (newlyCachedIntent) handledIntentTokens.add(intent.hashCode())
            explicitLink = if (repeatedIntent) null else intent.data?.toString()
            // Repeated lifecycle callbacks share the pending lookup; a direct link always runs.
            if (explicitLink == null && sharedLookup?.sessionId == grovsContext.sessionId) return@withLock null
            Lookup(committedLinks, grovsContext.sessionId, explicit = explicitLink != null, token = token).also {
                if (it.explicit) explicitLookups.add(it) else sharedLookup = it
                lookupsInFlight.add(it)
                armHold(token)
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
                    // Revocation is not consumption. Permit a later explicit call with this same
                    // intent to retry, without changing a newer intent's cache or replaying here.
                    if (!grovsContext.consent.isCurrent(token) && !lookup.committed && !repeatedIntent) {
                        if (newlyCachedIntent) handledIntentTokens.remove(intent.hashCode())
                        if (lastIntentHandledReference?.get() === intent) lastIntentHandledReference = null
                    }
                    // The last lookup out with no link committed lets the queued events go as they are.
                    if (!isClosed && lookupsInFlight.none { grovsContext.consent.isCurrent(it.token) }) releaseHold(token, delayEvents)
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
        if (grovsContext.consent.workToken(configuration) == null) {
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
        val token = grovsContext.consent.workToken(configuration) ?: return
        screenTracker.trackScreen(rawName = screenName, properties = properties, consentGeneration = token.generation)
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
        val consent = grovsContext.consent
        val token = currentConsentToken() ?: consent.tryAcquire(configuration) ?: return
        if (!consent.isCurrent(token)) return
        val pending = pendingScreenAliases ?: return

        val generation = aliasSyncGeneration
        when (consent.runOperation(token) { grovsService.syncScreenAliases(pending) }) {
            is LSResult.Success -> {
                // A newer setScreenAliases landed while this was in flight, or consent was withdrawn
                // and granted again; either way that response no longer describes the pending set
                // and must not clear it.
                if (generation == aliasSyncGeneration && consent.isCurrent(token)) {
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
        val token = grovsContext.consent.workToken(configuration) ?: return
        screenTracker.trackScreen(rawName = screenName, properties = null, dedupKey = screenClass, consentGeneration = token.generation)
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
        if (grovsContext.consent.workToken(configuration) == null) {
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
                    if (!continuation.isActive) {
                        referrerClient.endConnection()
                        return
                    }
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
     * The one long-lived attribute sync. Each change to the desired values, and each resync
     * request, cancels the send in flight and waits for it to stop before the next one starts. The
     * service retries forever, so this is what keeps two updates from ever being at the backend at
     * once and guarantees the last value written lands last.
     */
    private fun launchAttributesSync(scope: CoroutineScope): Job = scope.launch {
        combine(grovsContext.userAttributes, attributesResyncRequests) { desired, _ -> desired }
            .collectLatest { desired -> syncAttributes(desired) }
    }

    /**
     * Sends [desired] unless the backend already holds it. Only a success that lands while its
     * consent grant is still current acknowledges the value; anything else leaves it pending for
     * the next write, authentication or grant. Never throws: one failure must not end the sync.
     */
    private suspend fun syncAttributes(desired: UserAttributes) {
        if (desired == acknowledgedAttributes || authenticationState != AuthenticationState.AUTHENTICATED) return
        val consent = grovsContext.consent
        val token = consent.tryAcquire(configuration) ?: return
        try {
            val result = consent.runOperation(token) {
                grovsService.updateAttributes(identifier = desired.identifier, attributes = desired.attributes, pushToken = desired.pushToken)
            }
            if (result is LSResult.Success) acknowledgedAttributes = desired
        } catch (_: ConsentRevokedException) {
            // Revoked mid-send: the value stays pending for the next grant.
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLogger.instance.log(LogLevel.ERROR, "Failed to update attributes: ${e.message}")
        }
    }

    private fun resyncAttributes() {
        attributesResyncRequests.update { it + 1 }
    }

    /**
     * Takes the backend's identifier and attributes unless the host has set values the backend has
     * not confirmed yet. The compare-and-set loses to any concurrent host write, so that write is
     * never overwritten by the server's older values.
     */
    private fun adoptBackendAttributes(identifier: String?, attributes: Map<String, Any>?) {
        val acknowledged = acknowledgedAttributes
        val adopted = acknowledged.copy(identifier = identifier, attributes = attributes)
        if (grovsContext.userAttributes.compareAndSet(acknowledged, adopted)) acknowledgedAttributes = adopted
    }
}
