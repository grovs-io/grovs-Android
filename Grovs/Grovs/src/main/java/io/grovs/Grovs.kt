package io.grovs

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import io.grovs.handlers.ActivityProvider
import io.grovs.handlers.ClipboardHandler
import io.grovs.handlers.CommitKind
import io.grovs.handlers.finish
import io.grovs.handlers.ConsentRevokedException
import io.grovs.handlers.ConsentToken
import io.grovs.handlers.ConsentTransition
import io.grovs.handlers.GrovsContext
import io.grovs.handlers.GrovsManager
import io.grovs.handlers.NavigationScreenTracker
import io.grovs.handlers.NotificationsManager
import io.grovs.handlers.VisibleFragmentResolver
import io.grovs.handlers.launchOperation
import io.grovs.handlers.runOperation
import io.grovs.model.DebugLogger
import io.grovs.model.DeeplinkDetails
import io.grovs.model.LogLevel
import io.grovs.model.events.PaymentEventType
import io.grovs.model.exceptions.GrovsErrorCode
import io.grovs.model.exceptions.GrovsException
import io.grovs.service.CustomRedirects
import io.grovs.service.TrackingParams
import io.grovs.utils.FlowObservable
import io.grovs.utils.InstantCompat
import io.grovs.utils.LSResult
import io.grovs.utils.ScreenUtils
import io.grovs.utils.flowDelegate
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

fun interface GrovsDeeplinkListener {
    fun onDeeplinkReceived(deeplinkDetails: DeeplinkDetails)
}

fun interface GrovsLinkGenerationListener {
    fun onLinkGenerated(link:String?, error: GrovsException?)
}

fun interface GrovsLinkDetailsListener {
    fun onLinkDetails(linkDetails:Map<String, Any>?, error: GrovsException?)
}

fun interface GrovsNotificationsListener {
    fun onAutomaticNotificationClosed(isLast:Boolean)
}

public class Grovs: ActivityProvider {

    companion object {
        private val instance = Grovs()

        /// Indicates if the test environment should be used
        private var useTestEnvironment: Boolean
            get() = instance.grovsContext.settings.useTestEnvironment
            set(value) {
                instance.grovsContext.settings.useTestEnvironment = value
                instance.apiKey?.let {
                    checkConfiguration()
                }
            }

        /// Flow to listen for link and data from which the app was opened from.
        /// The value of this param is null if the app was not opened from a link.
        /// The data provided is same as the one from setOnDeeplinkReceivedListener. This is just for convenience when using kotlin coroutines api.
        @FlowObservable
        @get:FlowObservable
        val openedLinkDetails: DeeplinkDetails?
            get() = instance.openedLinkDetails

        /// The identifier for the current user, normally a userID. This will be visible in the grovs dashboard.
        var identifier: String?
            get() = instance.identifier
            set(value) {
                instance.identifier = value
            }

        /// The push token for the user. This property allows getting and setting the push notification token.
        var pushToken: String?
            get() = instance.pushToken
            set(value) {
                instance.pushToken = value
            }

        /// The attributes for the current user. This will be visible in the grovs dashboard.
        var attributes: Map<String, Any>?
            get() = instance.attributes
            set(value) {
                instance.attributes = value
            }

        /** Configures Grovs using the released 1.1.1 signature. */
        fun configure(
            application: Application,
            apiKey: String,
            useTestEnvironment: Boolean,
            baseURL: String? = null,
        ) {
            instance.configure(
                application = application,
                apiKey = apiKey,
                useTestEnvironment = useTestEnvironment,
                baseURL = baseURL,
            )
        }

        /** Configures Grovs and explicitly controls automatic screen tracking. */
        fun configure(
            application: Application,
            apiKey: String,
            useTestEnvironment: Boolean,
            baseURL: String?,
            autoTrackScreenViews: Boolean,
        ) {
            instance.configure(
                application = application,
                apiKey = apiKey,
                useTestEnvironment = useTestEnvironment,
                baseURL = baseURL,
                autoTrackScreenViews = autoTrackScreenViews,
            )
        }

        /**
         * Configures Grovs with custom link hosts for clipboard-assisted deferred deep linking.
         * - clipboardDomains: hosts your links are served from (e.g. `["links.example.com"]`).
         *   `*.sqd.link` and `*.grovs.link` hosts are always accepted; content on any other host is
         *   never sent for matching. Host validation occurs after reading clipboard text.
         */
        fun configure(
            application: Application,
            apiKey: String,
            useTestEnvironment: Boolean,
            baseURL: String?,
            autoTrackScreenViews: Boolean,
            clipboardDomains: List<String>?,
        ) {
            instance.configure(
                application = application,
                apiKey = apiKey,
                useTestEnvironment = useTestEnvironment,
                baseURL = baseURL,
                autoTrackScreenViews = autoTrackScreenViews,
                clipboardDomains = clipboardDomains,
            )
        }

        /**
         * Configures Grovs with an explicit consent state.
         * - enabled: `false` constructs the SDK but authenticates, tracks and sends nothing until
         *   `setSDK(true)` is called. Not persisted; pass the current consent on every launch.
         */
        fun configure(
            application: Application,
            apiKey: String,
            useTestEnvironment: Boolean,
            baseURL: String?,
            autoTrackScreenViews: Boolean,
            clipboardDomains: List<String>?,
            enabled: Boolean,
        ) {
            instance.configure(
                application = application,
                apiKey = apiKey,
                useTestEnvironment = useTestEnvironment,
                baseURL = baseURL,
                autoTrackScreenViews = autoTrackScreenViews,
                clipboardDomains = clipboardDomains,
                enabled = enabled,
            )
        }

        /// Toggles SDK consent at runtime.
        /// - Parameter enabled: `false` rejects new work and cancels outstanding operations;
        ///   queued events stay on the device and accepted local bookkeeping may finish. `true` authenticates if the SDK is not authenticated yet (which
        ///   records install/open once), or otherwise sends what was held. Not persisted.
        fun setSDK(enabled: Boolean) {
            instance.setSDK(enabled)
        }

        /// Sets the debug level for the SDK log messages.
        fun setDebug(level: LogLevel) {
            instance.setDebug(level)
        }

        /// Generates a link using kotlin coroutine style.
        ///
        /// - Parameters:
        ///   - title: The title of the link.
        ///   - subtitle: The subtitle of the link.
        ///   - imageURL: The URL of the image associated with the link.
        ///   - data: Additional data for the link.
        ///   - tags: Tags for the link.
        ///   - customRedirects: Override the default redirects for a link.
        ///   - showPreviewIos: Show the link preview before redirecting on iOS platform.
        ///   - showPreviewAndroid: Show the link preview before redirecting on Android platform.
        ///   - copyToClipboardIos: Override the project's copy-to-clipboard setting for iOS; `null` inherits it.
        ///   - copyToClipboardAndroid: Override the project's copy-to-clipboard setting for Android; `null` inherits it.
        ///   - tracking: Provide utm tracking parameters for your link.
        suspend fun generateLink(title: String? = null,
                                 subtitle: String? = null,
                                 imageURL: String? = null,
                                 data: Map<String, Serializable>? = null,
                                 tags: List<String>? = null,
                                 customRedirects: CustomRedirects? = null,
                                 showPreviewIos: Boolean? = null,
                                 showPreviewAndroid: Boolean? = null,
                                 copyToClipboardIos: Boolean? = null,
                                 copyToClipboardAndroid: Boolean? = null,
                                 tracking: TrackingParams? = null): String {
            return instance.generateLink(title = title,
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

        /// Generates a link.
        ///
        /// - Parameters:
        ///   - title: The title of the link.
        ///   - subtitle: The subtitle of the link.
        ///   - imageURL: The URL of the image associated with the link.
        ///   - data: Additional data for the link.
        ///   - tags: Tags for the link.
        ///   - customRedirects: Override the default redirects for a link.
        ///   - showPreviewIos: Show the link preview before redirecting on iOS platform.
        ///   - showPreviewAndroid: Show the link preview before redirecting on Android platform.
        ///   - copyToClipboardIos: Override the project's copy-to-clipboard setting for iOS; `null` inherits it.
        ///   - copyToClipboardAndroid: Override the project's copy-to-clipboard setting for Android; `null` inherits it.
        ///   - tracking: Provide utm tracking parameters for your link.
        ///   - lifecycleOwner: An optional LifecycleOwner to use when calling the listener, by default global one will be used.
        ///   - listener: A closure to be executed after generating the link.
        fun generateLink(title: String? = null,
                         subtitle: String? = null,
                         imageURL: String? = null,
                         data: Map<String, Serializable>? = null,
                         tags: List<String>? = null,
                         customRedirects: CustomRedirects? = null,
                         showPreviewIos: Boolean? = null,
                         showPreviewAndroid: Boolean? = null,
                         copyToClipboardIos: Boolean? = null,
                         copyToClipboardAndroid: Boolean? = null,
                         tracking: TrackingParams? = null,
                         lifecycleOwner: LifecycleOwner? = null,
                         listener: GrovsLinkGenerationListener
        ) {
            instance.generateLink(title, subtitle, imageURL, data, tags, customRedirects, showPreviewIos, showPreviewAndroid, copyToClipboardIos, copyToClipboardAndroid, tracking, lifecycleOwner, listener)
        }

        /// Get link details using kotlin coroutine style.
        ///
        /// - Parameters:
        ///   - path: The last part of a grovs link.
        suspend fun linkDetails(path: String): Map<String, Any> {
            return instance.linkDetails(path = path)
        }

        /// Get link details.
        ///
        /// - Parameters:
        ///   - path: The last part of a grovs link.
        ///   - lifecycleOwner: An optional LifecycleOwner to use when calling the listener, by default global one will be used.
        ///   - listener: A closure to be executed with the link details after they are fetched.
        fun linkDetails(path: String,
                         lifecycleOwner: LifecycleOwner? = null,
                         listener: GrovsLinkDetailsListener
        ) {
            instance.linkDetails(path = path, lifecycleOwner = lifecycleOwner, listener = listener)
        }

        /// This needs to be called on the launcher activity onStart() to allow the SDK to handle incoming links
        fun onStart(launcherActivity: Activity? = null) {
            instance.onStart(launcherActivity = launcherActivity)
        }

        /// This needs to be called on the launcher activity onNewIntent() to allow the SDK to handle incoming links
        fun onNewIntent(intent: Intent?, launcherActivity: Activity? = null) {
            instance.onNewIntent(intent, launcherActivity = launcherActivity)
        }

        /// Register a listener to receive the link and data from which the app was opened.
        ///
        /// - Parameters:
        ///   - launcherActivity: The launcher activity.
        ///   - listener: A listener to receive the link and data from which the app was opened.
        fun setOnDeeplinkReceivedListener(launcherActivity: Activity?, listener: GrovsDeeplinkListener) {
            instance.setOnDeeplinkReceivedListener(launcherActivity, listener)
        }

        /// Log a purchase that happened using the google play billing library (in app purchase).
        ///
        /// - Parameters:
        ///   - originalJson: The original json of the purchase (purchase.originalJson).
        fun logInAppPurchase(originalJson: String) {
            instance.logInAppPurchase(originalJson = originalJson)
        }

        /// Tracks a custom analytics event.
        ///
        /// - Parameters:
        ///   - name: the event name. Must not be blank, and must not be one of the SDK's reserved
        ///     names (view, open, install, reinstall, app_open, time_spent, reactivation,
        ///     user_referred, custom, screen_view) — those are rejected and logged.
        ///   - properties: arbitrary metadata. Non-finite numbers are dropped; Date, URL and UUID
        ///     are coerced to strings. Dropped entirely if it serializes to over 8KB.
        ///   - tags: up to 20 tags, each up to 255 characters. Merged with any global tags.
        fun track(name: String, properties: Map<String, Any>? = null, tags: List<String>? = null) {
            instance.track(name = name, properties = properties, tags = tags)
        }

        /// Sets tags merged onto every subsequently tracked event. Pass null to clear.
        fun setGlobalTags(tags: List<String>? = null) {
            instance.setGlobalTags(tags)
        }

        /// Tracks a screen view manually. Use when auto-tracking is off, or for screens the SDK
        /// cannot see (custom views, Compose destinations).
        fun trackScreenView(screenName: String, properties: Map<String, Any>? = null) {
            instance.trackScreenView(screenName = screenName, properties = properties)
        }

        /// Maps Activity/Fragment class names to friendly names shown in the dashboard.
        /// e.g. mapOf("MainActivity" to "Home").
        fun setScreenAliases(aliases: Map<String, String>) {
            instance.setScreenAliases(aliases)
        }

        /// Tracks screen views from a Jetpack Navigation [NavController]. Each destination change is
        /// reported as a screen (using the destination's route, then its label, then its display
        /// name). This is the recommended way to track Navigation-Compose and route-based graphs,
        /// which the lifecycle-based auto-tracker cannot see.
        ///
        /// Call once per NavController, e.g. right after you set its graph. Safe to call repeatedly
        /// on the same controller — duplicate registrations are ignored. The SDK keeps only a weak
        /// reference to the controller, so this does not leak the hosting Activity/Fragment.
        ///
        /// Note: with Fragment-based navigation this can overlap the lifecycle auto-tracker and
        /// produce duplicate screen views under different names. For those apps, either disable
        /// automatic screen tracking (`autoTrackScreenViews = false`) and rely on this, or use this
        /// only for Compose/route-based graphs.
        fun trackNavigation(navController: NavController) {
            instance.trackNavigation(navController)
        }

        /// Log a custom purchase for your project. If you are making purchases outside of google play, you can use this method to log them in grovs.
        ///
        /// - Parameters:
        ///   - type: The type of the purchase event, a buy or a cancelled event.
        ///   - priceInCents: The purchase price in cents.
        ///   - currency: The currency of the purchase.
        ///   - productId:
        ///   - startDate:
        fun logCustomPurchase(type: PaymentEventType, priceInCents: Int, currency: String, productId: String, startDate: InstantCompat? = InstantCompat.now()) {
            instance.logCustomPurchase(type = type,
                priceInCents = priceInCents,
                currency = currency,
                productId = productId,
                startDate = startDate)
        }

        /// Register a listener for receiving automatic notifications events.
        ///
        /// - Parameters:
        ///   - listener: A listener to receive events about automatic notifications.
        fun setOnAutomaticNotificationsListener(listener: GrovsNotificationsListener) {
            instance.setOnAutomaticNotificationsListener(listener = listener)
        }

        /// Show the notifications screen.
        ///
        /// - Parameters:
        ///   - listener: A lambda function to be called when the screen is dismissed.
        fun displayMessagesFragment(onDismissed: (()->Unit)?) {
            instance.displayMessagesFragment(onDismissed)
        }

        /// Get the number of unread notifications this device currently has.
        suspend fun numberOfUnreadMessages(): Int? {
            return instance.numberOfUnreadMessages()
        }

        /// Get the number of unread notifications this device currently has.
        fun numberOfUnreadMessages(lifecycleOwner: LifecycleOwner? = null, onResult: ((Int?)->Unit)?) {
            return instance.numberOfUnreadMessages(lifecycleOwner = lifecycleOwner, onResult = onResult)
        }

        /// Checks the configuration validity.
        private fun checkConfiguration() {
            instance.checkConfiguration()
        }

    }

    var openedLinkDetails: DeeplinkDetails? by flowDelegate(null)

    /// The identifier for the current user, normally a userID. This will be visible in the grovs dashboard.
    private var identifier: String?
        get() = grovsManager?.identifier
        set(value) {
            grovsManager?.identifier = value
        }

    /// The push token for the user. This property allows getting and setting the push notification token.
    var pushToken: String?
        get() = grovsManager?.pushToken
        set(value) {
            grovsManager?.pushToken = value
        }

    /// The attributes for the current user. This will be visible in the grovs dashboard.
    private var attributes: Map<String, Any>?
        get() = grovsManager?.attributes
        set(value) {
            grovsManager?.attributes = value
        }

    private var grovsManager: GrovsManager? = null
    private var notificationsManager: NotificationsManager? = null

    // This is used for linking the SDK to your account
    private var apiKey: String? = null

    private var application: Application? = null

    private var deeplinkListener: GrovsDeeplinkListener? = null
    private var grovsNotificationsListener: GrovsNotificationsListener? = null

    private var launcherActivityReference: WeakReference<Activity>? = null
    private var currentActivityReference: WeakReference<Activity>? = null
        set(value) {
            field = value
            if ((field != null) && (grovsManager?.authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED)) {
                notificationsManager?.displayAutomaticNotificationsIfNeeded()
            }
            currentActivityReference?.get()?.let {
                ScreenUtils.getScreenResolution(context = it)
            }
        }

    private var handleIntentConflict = false
    private var lastOnStartTime: Long = 0
    private var lastLinkMatched: String? = null
    private val defaultIntent = Intent()

    private var grovsContext = GrovsContext()

    private var authenticationJob: Job? = null

    /** The pending screen resolution per Activity, so pause/destroy and re-resumes can cancel it. */
    private val pendingScreenResolutionJobs = ConcurrentHashMap<Activity, Job>()

    private val fragmentLifecycleObserver = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) {
            fragment.activity?.let(::scheduleScreenResolution)
        }
    }

    /**
     * Resolves which screen is on display and reports it. Resolution is posted to the main looper
     * rather than run inline, so all lifecycle callbacks of one navigation (an Activity plus its
     * Fragments, in either order) coalesce into the single most recent job, which then walks the
     * fragment tree once to find the visible leaf.
     */
    private fun scheduleScreenResolution(activity: Activity) {
        val manager = grovsManager ?: return
        if (!grovsContext.settings.autoTrackScreenViews) return
        val token = grovsContext.consent.tryAcquire(manager.configuration) ?: return
        val job = grovsContext.consent.launchOperation(token, context = Dispatchers.Main) {
            val lifecycleOwner = activity as? LifecycleOwner
            if (lifecycleOwner != null &&
                !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) {
                return@launchOperation
            }
            val leaf = (activity as? FragmentActivity)
                ?.supportFragmentManager
                ?.let(VisibleFragmentResolver::findVisibleLeaf)
                ?.javaClass
            val screenName = leaf?.simpleName ?: activity.javaClass.simpleName
            // Fully-qualified name is the dedup identity: two distinct screens that share a simpleName
            // must not be collapsed by the dedup window.
            val screenClass = leaf?.name ?: activity.javaClass.name

            withContext(grovsContext.serialDispatcher) {
                manager.autoTrackScreen(screenName, screenClass)
            }
        } ?: return
        pendingScreenResolutionJobs.put(activity, job)?.cancel()
        // Fires immediately if the job already finished, so the map cannot leak completed jobs.
        job.invokeOnCompletion { pendingScreenResolutionJobs.remove(activity, job) }
    }

    private val applicationLifecycleObserver: Application.ActivityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        private var numStarted = 0

        override fun onActivityCreated(activity: Activity, p1: Bundle?) {
            // Fragments are where most modern apps' screens actually live — a single-Activity app
            // would otherwise report one screen for its entire lifetime.
            if (activity is FragmentActivity) {
                activity.supportFragmentManager
                    .registerFragmentLifecycleCallbacks(fragmentLifecycleObserver, true)
            }
        }
        override fun onActivityStarted(activity: Activity) {
            currentActivityReference = WeakReference(activity)

            if (numStarted == 0) {
                // App is in foreground
                onAppForegrounded()
            }
            numStarted++
        }
        override fun onActivityResumed(activity: Activity) {
            currentActivityReference = WeakReference(activity)
            scheduleScreenResolution(activity)
        }
        override fun onActivityPaused(activity: Activity) {
            if (currentActivityReference?.get() == activity) currentActivityReference = null
            pendingScreenResolutionJobs.remove(activity)?.cancel()
        }
        override fun onActivityStopped(activity: Activity) {
            if (currentActivityReference?.get() == activity) currentActivityReference = null

            numStarted--
            if (numStarted == 0) {
                // App is in background
                onAppBackgrounded()
            }
        }
        override fun onActivitySaveInstanceState(activity: Activity, p1: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {
            if (currentActivityReference?.get() == activity) currentActivityReference = null
            pendingScreenResolutionJobs.remove(activity)?.cancel()
        }

        private fun onAppForegrounded() {
            // App moved to the foreground
            DebugLogger.instance.log(LogLevel.INFO, "App is in the foreground")

            grovsContext.isForeground = true
            val previousSession = grovsContext.sessionId
            grovsContext.rotateSessionIfNeeded()
            val sessionRotated = grovsContext.sessionId != previousSession

            collect { manager ->
                // ScreenTracker's dedup state is confined to serialDispatcher, so reset it here rather
                // than on the caller's thread. Must precede any screen tracking on this dispatcher.
                if (sessionRotated) {
                    manager.resetScreenDedup()
                }

                authenticationJob?.join()
                manager.onAppForegrounded()
            }
        }

        private fun onAppBackgrounded() {
            // App moved to the background
            DebugLogger.instance.log(LogLevel.INFO, "App is in the background")
            grovsContext.isForeground = false
            grovsContext.markBackgrounded()
            grovsManager?.onAppBackgrounded()
        }
    }

    fun configure(application: Application, apiKey: String, useTestEnvironment: Boolean, baseURL: String? = null) {
        configure(
            application = application,
            apiKey = apiKey,
            useTestEnvironment = useTestEnvironment,
            baseURL = baseURL,
            autoTrackScreenViews = true,
        )
    }

    fun configure(
        application: Application,
        apiKey: String,
        useTestEnvironment: Boolean,
        baseURL: String?,
        autoTrackScreenViews: Boolean,
    ) {
        configure(
            application = application,
            apiKey = apiKey,
            useTestEnvironment = useTestEnvironment,
            baseURL = baseURL,
            autoTrackScreenViews = autoTrackScreenViews,
            clipboardDomains = null,
        )
    }

    fun configure(
        application: Application,
        apiKey: String,
        useTestEnvironment: Boolean,
        baseURL: String?,
        autoTrackScreenViews: Boolean,
        clipboardDomains: List<String>?,
    ) {
        configure(
            application = application,
            apiKey = apiKey,
            useTestEnvironment = useTestEnvironment,
            baseURL = baseURL,
            autoTrackScreenViews = autoTrackScreenViews,
            clipboardDomains = clipboardDomains,
            enabled = true,
        )
    }

    fun configure(
        application: Application,
        apiKey: String,
        useTestEnvironment: Boolean,
        baseURL: String?,
        autoTrackScreenViews: Boolean,
        clipboardDomains: List<String>?,
        enabled: Boolean,
    ) {
        this.grovsContext.consent.retireConfiguration(enabled = enabled)
        this.grovsContext.requiresAuthentication = true
        this.grovsContext.authenticatedConfiguration = null
        this.grovsContext.grovsId = null
        this.apiKey = apiKey
        this.application = application
        this.grovsContext.settings.useTestEnvironment = useTestEnvironment
        this.grovsContext.settings.baseURL = baseURL
        this.grovsContext.settings.autoTrackScreenViews = autoTrackScreenViews
        this.grovsContext.settings.clipboardDomains = ClipboardHandler.normalizeDomains(clipboardDomains)
        // Stop the previous manager's custom-events flush timer when configure() is called again.
        grovsManager?.close()
        // A job chained off the previous manager must not keep retrying against a manager that is
        // about to be replaced: checkConfiguration() joins this (now cancelled) job before it
        // authenticates the new one, so joining returns immediately instead of waiting out retries.
        authenticationJob?.cancel()

        grovsManager = GrovsManager(context = application.applicationContext,
            application = application,
            grovsContext = grovsContext,
            apiKey = apiKey,
            activityProvider = this)

        notificationsManager = NotificationsManager(context = application.applicationContext,
            grovsContext = grovsContext,
            apiKey = apiKey,
            activityProvider = this)

        checkConfiguration(awaiting = grovsContext.consent.pendingCommits())
        // registerActivityLifecycleCallbacks adds to a list, so registering on every configure()
        // would duplicate lifecycle callbacks (and double-report screen views).
        application.unregisterActivityLifecycleCallbacks(applicationLifecycleObserver)
        application.registerActivityLifecycleCallbacks(applicationLifecycleObserver)
    }

    fun setSDK(enabled: Boolean) {
        val consent = grovsContext.consent
        // One atomic transition: a repeated value changes nothing and schedules nothing. Revocation
        // invalidates every admitted operation synchronously and cancels them off this thread, so
        // this returns promptly however slow an operation's cancellation turns out to be.
        val manager = grovsManager
        val cutoff = InstantCompat.now()
        // Reserve storage-only closure before revocation; the next enable waits for this permit.
        val endingSegment = if (!enabled) manager?.let { m ->
            consent.tryAcquire(m.configuration)?.let { consent.tryAdmitCommit(it, CommitKind.STORAGE_TRANSACTION) }
        } else null
        val transition = if (enabled) consent.enable() else consent.revoke()
        if (!transition.changed) {
            endingSegment?.close()
            return
        }
        DebugLogger.instance.log(LogLevel.INFO, "SDK setEnabled to: $enabled")

        // The controller owns cancellation. Finish only the engagement bookkeeping admitted above.
        if (!enabled) {
            if (endingSegment != null && manager != null) {
                val authentication = authenticationJob
                manager.configuration.scope.launch(NonCancellable + grovsContext.serialDispatcher) {
                    endingSegment.finish {
                        authentication?.join()
                        manager.onDisabled(cutoff)
                    }
                }
            }
            return
        }

        // Resume work waits for the previous generation's cleanup - including any launch commit
        // admitted just before the revocation - so it cannot duplicate what that commit is finishing.
        val priorWork = (transition as ConsentTransition.Enabled).priorWork
        if (manager == null) return
        if (manager.authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED) {
            consent.launchOperation(manager.configuration, context = grovsContext.serialDispatcher) {
                priorWork.join()
                manager.onEnabled()
            }
        } else {
            checkConfiguration(awaiting = priorWork)
        }
    }

    fun setDebug(level: LogLevel) {
        grovsContext.settings.debugLevel = level
    }

    suspend fun generateLink(title: String? = null,
                             subtitle: String? = null,
                             imageURL: String? = null,
                             data: Map<String, Serializable>? = null,
                             tags: List<String>? = null,
                             customRedirects: CustomRedirects? = null,
                             showPreviewIos: Boolean? = null,
                             showPreviewAndroid: Boolean? = null,
                             copyToClipboardIos: Boolean? = null,
                             copyToClipboardAndroid: Boolean? = null,
                             tracking: TrackingParams?): String {
        var link: String? = null
        grovsManager?.let { manager ->
            if (manager.authenticationState == GrovsManager.AuthenticationState.RETRYING) {
                val message = "The device is not yet authenticated, check internet connection and try again."
                DebugLogger.instance.log(LogLevel.ERROR, message)
                throw GrovsException(message, GrovsErrorCode.LINK_GENERATION_ERROR)
            }

            val token = explicitToken(manager)
                ?: throw GrovsException(CONSENT_REJECTED, GrovsErrorCode.LINK_GENERATION_ERROR)
            val result = explicitRequest(token) {
                manager.generateLink(
                    title = title,
                    subtitle = subtitle,
                    imageURL = imageURL,
                    data = data,
                    tags = tags,
                    customRedirects = customRedirects,
                    showPreviewIos = showPreviewIos,
                    showPreviewAndroid = showPreviewAndroid,
                    copyToClipboardIos = copyToClipboardIos,
                    copyToClipboardAndroid = copyToClipboardAndroid,
                    tracking = tracking
                )
            } ?: throw GrovsException(CONSENT_REJECTED, GrovsErrorCode.LINK_GENERATION_ERROR)

            withContext(Dispatchers.Main) {
                if (!isConsented(token)) {
                    throw GrovsException(CONSENT_REJECTED, GrovsErrorCode.LINK_GENERATION_ERROR)
                }
                when (result) {
                    is LSResult.Success -> {
                        link = result.data.link
                    }
                    is LSResult.Error -> {
                        throw GrovsException(result.exception.message, GrovsErrorCode.LINK_GENERATION_ERROR)
                    }
                }
            }
        } ?: run {
            DebugLogger.instance.log(LogLevel.ERROR,"The SDK is not properly configured. Call Grovs.configure(application: Application, apiKey: String) first.")
            throw GrovsException("The sdk is not initialized. Initialize the sdk before generating links.", GrovsErrorCode.SDK_NOT_INITIALIZED)
        }

        link?.let { link ->
            return link
        } ?: run {
            throw GrovsException("Failed to generate the link.", GrovsErrorCode.LINK_GENERATION_ERROR)
        }
    }

    fun generateLink(title: String? = null,
                     subtitle: String? = null,
                     imageURL: String? = null,
                     data: Map<String, Serializable>? = null,
                     tags: List<String>? = null,
                     customRedirects: CustomRedirects? = null,
                     showPreviewIos: Boolean? = null,
                     showPreviewAndroid: Boolean? = null,
                     copyToClipboardIos: Boolean? = null,
                     copyToClipboardAndroid: Boolean? = null,
                     tracking: TrackingParams?,
                     lifecycleOwner: LifecycleOwner? = null,
                     listener: GrovsLinkGenerationListener
    ) {
        grovsManager?.let { manager ->
            if (manager.authenticationState == GrovsManager.AuthenticationState.RETRYING) {
                val message = "The device is not yet authenticated, check internet connection and try again."
                DebugLogger.instance.log(LogLevel.ERROR, message)
                listener.onLinkGenerated(null, GrovsException(message, GrovsErrorCode.LINK_GENERATION_ERROR))
                return
            }

            if (lifecycleOwner == null) {
                DebugLogger.instance.log(LogLevel.INFO,"LifecycleScope not provided, will use global scope.")
            }

            val token = explicitToken(manager) ?: run {
                (lifecycleOwner?.lifecycleScope ?: GlobalScope).launch(Dispatchers.Main) {
                    listener.onLinkGenerated(null, GrovsException(CONSENT_REJECTED, GrovsErrorCode.LINK_GENERATION_ERROR))
                }
                return
            }
            val scope = (lifecycleOwner?.lifecycleScope ?: GlobalScope)
            scope.launch {
                val result = explicitRequest(token) {
                    manager.generateLink(
                        title = title,
                        subtitle = subtitle,
                        imageURL = imageURL,
                        data = data,
                        tags = tags,
                        customRedirects = customRedirects,
                        showPreviewIos = showPreviewIos,
                        showPreviewAndroid = showPreviewAndroid,
                        copyToClipboardIos = copyToClipboardIos,
                        copyToClipboardAndroid = copyToClipboardAndroid,
                        tracking = tracking
                    )
                }

                withContext(Dispatchers.Main) {
                    if (result == null || !isConsented(token)) {
                        listener.onLinkGenerated(null, GrovsException(CONSENT_REJECTED, GrovsErrorCode.LINK_GENERATION_ERROR))
                        return@withContext
                    }
                    when (result) {
                        is LSResult.Success -> {
                            listener.onLinkGenerated(result.data.link, null)
                        }
                        is LSResult.Error -> {
                            listener.onLinkGenerated(null, GrovsException(result.exception.message, GrovsErrorCode.LINK_GENERATION_ERROR))
                        }
                    }
                }
            }
        } ?: run {
            val message = "The SDK is not properly configured. Call Grovs.configure(application: Application, apiKey: String) first."
            DebugLogger.instance.log(LogLevel.ERROR, message)
            listener.onLinkGenerated(null, GrovsException(message, GrovsErrorCode.LINK_GENERATION_ERROR))
        }
    }

    suspend fun linkDetails(path: String): Map<String, Any> {
        var linkDetails: Map<String, Any>? = null
        grovsManager?.let { manager ->
            if (manager.authenticationState == GrovsManager.AuthenticationState.RETRYING) {
                val message = "The device is not yet authenticated, check internet connection and try again."
                DebugLogger.instance.log(LogLevel.ERROR, message)
                throw GrovsException(message, GrovsErrorCode.LINK_GENERATION_ERROR)
            }

            val token = explicitToken(manager)
                ?: throw GrovsException(CONSENT_REJECTED, GrovsErrorCode.LINK_DETAILS_ERROR)
            val result = explicitRequest(token) { manager.linkDetails(path = path) }
                ?: throw GrovsException(CONSENT_REJECTED, GrovsErrorCode.LINK_DETAILS_ERROR)

            withContext(Dispatchers.Main) {
                if (!isConsented(token)) {
                    throw GrovsException(CONSENT_REJECTED, GrovsErrorCode.LINK_DETAILS_ERROR)
                }
                when (result) {
                    is LSResult.Success -> {
                        linkDetails = result.data.link
                    }
                    is LSResult.Error -> {
                        throw GrovsException(result.exception.message, GrovsErrorCode.LINK_DETAILS_ERROR)
                    }
                }
            }
        } ?: run {
            DebugLogger.instance.log(LogLevel.ERROR,"The SDK is not properly configured. Call Grovs.configure(application: Application, apiKey: String) first.")
            throw GrovsException("The sdk is not initialized. Initialize the sdk before generating links.", GrovsErrorCode.SDK_NOT_INITIALIZED)
        }

        linkDetails?.let { linkDetails ->
            return linkDetails
        } ?: run {
            throw GrovsException("Failed to get the link details.", GrovsErrorCode.LINK_DETAILS_ERROR)
        }
    }

    fun linkDetails(path: String,
                     lifecycleOwner: LifecycleOwner? = null,
                     listener: GrovsLinkDetailsListener
    ) {
        grovsManager?.let { manager ->
            if (manager.authenticationState == GrovsManager.AuthenticationState.RETRYING) {
                val message = "The device is not yet authenticated, check internet connection and try again."
                DebugLogger.instance.log(LogLevel.ERROR, message)
                listener.onLinkDetails(null, GrovsException(message, GrovsErrorCode.LINK_DETAILS_ERROR))
                return
            }

            if (lifecycleOwner == null) {
                DebugLogger.instance.log(LogLevel.INFO,"LifecycleScope not provided, will use global scope.")
            }

            val token = explicitToken(manager) ?: run {
                (lifecycleOwner?.lifecycleScope ?: GlobalScope).launch(Dispatchers.Main) {
                    listener.onLinkDetails(null, GrovsException(CONSENT_REJECTED, GrovsErrorCode.LINK_DETAILS_ERROR))
                }
                return
            }
            val scope = (lifecycleOwner?.lifecycleScope ?: GlobalScope)
            scope.launch {
                val result = explicitRequest(token) { manager.linkDetails(path = path) }

                withContext(Dispatchers.Main) {
                    if (result == null || !isConsented(token)) {
                        listener.onLinkDetails(null, GrovsException(CONSENT_REJECTED, GrovsErrorCode.LINK_DETAILS_ERROR))
                        return@withContext
                    }
                    when (result) {
                        is LSResult.Success -> {
                            listener.onLinkDetails(result.data.link, null)
                        }
                        is LSResult.Error -> {
                            listener.onLinkDetails(null, GrovsException(result.exception.message, GrovsErrorCode.LINK_DETAILS_ERROR))
                        }
                    }
                }
            }
        } ?: run {
            val message = "The SDK is not properly configured. Call Grovs.configure(application: Application, apiKey: String) first."
            DebugLogger.instance.log(LogLevel.ERROR, message)
            listener.onLinkDetails(null, GrovsException(message, GrovsErrorCode.LINK_DETAILS_ERROR))
        }
    }

    fun onStart(launcherActivity: Activity? = null) {
        lastOnStartTime = SystemClock.elapsedRealtime()
        handleIntentConflict = false

        launcherActivity?.let {
            launcherActivityReference = WeakReference(launcherActivity)
        }
        handleIntent(launcherActivityReference?.get()?.intent, delayEvents = true, cacheIntent = true)
    }

    fun onNewIntent(intent: Intent?, launcherActivity: Activity? = null) {
        handleIntentConflict = SystemClock.elapsedRealtime() - lastOnStartTime < 2_000L

        launcherActivity?.let {
            launcherActivityReference = WeakReference(launcherActivity)
        }
        handleIntent(intent, delayEvents = false)
    }

    fun setOnDeeplinkReceivedListener(launcherActivity: Activity?, listener: GrovsDeeplinkListener) {
        launcherActivity?.let {
            launcherActivityReference = WeakReference(launcherActivity)
        }
        deeplinkListener = listener
    }

    fun logInAppPurchase(originalJson: String) {
        collect { manager ->
            authenticationJob?.join()
            manager.logInAppPurchase(originalJson = originalJson)
        }
    }

    fun track(name: String, properties: Map<String, Any>? = null, tags: List<String>? = null) {
        collect { manager -> manager.track(name = name, properties = properties, tags = tags) }
    }

    fun setGlobalTags(tags: List<String>? = null) {
        // globalTags is confined to serialDispatcher and unsynchronized, so it must be written there.
        val manager = grovsManager ?: return
        manager.configuration.scope.launch(grovsContext.serialDispatcher) { manager.setGlobalTags(tags) }
    }

    fun trackScreenView(screenName: String, properties: Map<String, Any>? = null) {
        collect { manager -> manager.trackScreenView(screenName = screenName, properties = properties) }
    }

    fun trackNavigation(navController: NavController) {
        // Explicit opt-in: routed through the manual trackScreenView path so it works even when
        // lifecycle-based auto-tracking is disabled (the "use NavController instead" workflow).
        NavigationScreenTracker.attach(navController) { screenName ->
            trackScreenView(screenName = screenName, properties = null)
        }
    }

    fun setScreenAliases(aliases: Map<String, String>) {
        // ScreenTracker.aliases is confined to serialDispatcher; the backend sync needs authentication.
        val manager = grovsManager ?: return
        manager.configuration.scope.launch(grovsContext.serialDispatcher) { manager.setScreenAliases(aliases) }
    }

    fun logCustomPurchase(type: PaymentEventType, priceInCents: Int, currency: String, productId: String, startDate: InstantCompat? = InstantCompat.now()) {
        collect { manager ->
            authenticationJob?.join()
            manager.logCustomPurchase(type = type,
                priceInCents = priceInCents,
                currency = currency,
                productId = productId,
                startDate = startDate)
        }
    }

    fun setOnAutomaticNotificationsListener(listener: GrovsNotificationsListener) {
        grovsNotificationsListener = listener
    }

    fun displayMessagesFragment(onDismissed: (()->Unit)?): Boolean {
        val manager = grovsManager ?: return false
        // Checked before anything is created: a disabled SDK starts no request and shows no
        // fragment, and answers false rather than opening a view it may not populate.
        if (explicitToken(manager) == null) {
            DebugLogger.instance.log(LogLevel.INFO, "SDK consent not granted - not displaying the messages fragment")
            return false
        }
        notificationsManager?.let { notificationsManager ->
            return notificationsManager.displayNotificationsViewController(onDismissed = onDismissed)
        } ?: run {
            return false
        }
    }

    suspend fun numberOfUnreadMessages(): Int? {
        if (grovsManager?.authenticationState == GrovsManager.AuthenticationState.RETRYING) {
            val message = "The device is not yet authenticated, check internet connection and try again."
            DebugLogger.instance.log(LogLevel.ERROR, message)
            return null
        }

        val manager = grovsManager ?: return null
        val token = explicitToken(manager) ?: run {
            DebugLogger.instance.log(LogLevel.INFO, "SDK consent not granted - no unread count")
            return null
        }
        val result = explicitRequest(token) {
            notificationsManager?.numberOfUnreadNotifications() ?: NO_COUNT
        } ?: return null
        // Checked once more before the caller sees it, so a count that went stale while it was
        // handed back is reported as no answer rather than as a fresh one.
        if (!isConsented(token) || result === NO_COUNT) return null
        return result as? Int
    }

    fun numberOfUnreadMessages(lifecycleOwner: LifecycleOwner? = null, onResult: ((Int?)->Unit)?) {
        if (grovsManager?.authenticationState == GrovsManager.AuthenticationState.RETRYING) {
            val message = "The device is not yet authenticated, check internet connection and try again."
            DebugLogger.instance.log(LogLevel.ERROR, message)
            onResult?.invoke(null)
            return
        }

        if (lifecycleOwner == null) {
            DebugLogger.instance.log(LogLevel.INFO,"LifecycleScope not provided, will use global scope.")
        }

        val manager = grovsManager
        val token = manager?.let { explicitToken(it) }
        if (token == null) {
            DebugLogger.instance.log(LogLevel.INFO, "SDK consent not granted or SDK not configured - no unread count")
        }
        val scope = (lifecycleOwner?.lifecycleScope ?: GlobalScope)
        scope.launch {
            // Answered on the main thread like every other outcome, never inline on the caller's
            // thread: an unconfigured or unconsented SDK keeps the callback's existing contract.
            if (token == null) {
                withContext(Dispatchers.Main) { onResult?.invoke(null) }
                return@launch
            }
            val result = explicitRequest(token) {
                notificationsManager?.numberOfUnreadNotifications() ?: NO_COUNT
            }

            withContext(Dispatchers.Main) {
                // Exactly one callback on every path: a revoked request answers null rather than
                // leaving the caller waiting, and a stale success is not reported as a count.
                onResult?.invoke(if (result == null || !isConsented(token)) null else result as? Int)
            }
        }

    }

    /**
     * The message an explicit request fails with when consent does not admit it, or was withdrawn
     * before its result could be handed back. It is the method's existing error shape, not a new
     * one: suspend callers get a [GrovsException], listener callers get one error completion.
     */
    private val CONSENT_REJECTED: String
        get() = "The SDK is not enabled. Grant consent with Grovs.setSDK(true) and try again."

    /**
     * Stands in for a null unread count inside a consent operation, so "the request was revoked"
     * and "the backend answered no count" stay distinguishable through a non-null-typed helper.
     */
    private val NO_COUNT: Any get() = NoCount

    private object NoCount

    private fun explicitToken(manager: GrovsManager): ConsentToken? =
        grovsContext.consent.tryAcquire(manager.configuration)

    /**
     * Runs one explicit public request under [token] and returns its result, or null when consent
     * revoked it before it could be handed back.
     *
     * The worker is a registered child of the caller: revocation cancels that child, never this
     * function and never the caller's own job, so the completion mapping below it always runs and
     * an active caller gets exactly one answer instead of a silently dropped listener. Ordinary
     * host cancellation still propagates as cancellation.
     */
    private suspend fun <T : Any> explicitRequest(token: ConsentToken, block: suspend () -> T): T? =
        try {
            grovsContext.consent.runOperation(token) {
                withContext(grovsContext.serialDispatcher) {
                    authenticationJob?.join()
                    block()
                }
            }
        } catch (e: ConsentRevokedException) {
            DebugLogger.instance.log(LogLevel.INFO, "SDK consent (${e.reason}) - the request was rejected")
            null
        }

    /**
     * True while [token] is still current. Called on the main thread immediately before host code
     * runs, so a result that went stale while queued for main dispatch is never published.
     */
    private fun isConsented(token: ConsentToken): Boolean = grovsContext.consent.isCurrent(token)

    private fun collect(block: suspend (GrovsManager) -> Unit) {
        val manager = grovsManager ?: return
        grovsContext.consent.launchOperation(
            manager.configuration,
            context = grovsContext.serialDispatcher,
        ) {
            block(manager)
        }
    }

    private fun checkConfiguration(awaiting: Job? = null) {
        instance.apiKey?.let { apiKey ->
            grovsManager?.let { manager ->
                val previousAuthenticationJob = authenticationJob
                authenticationJob = grovsContext.consent.launchOperation(
                    manager.configuration,
                    context = grovsContext.serialDispatcher,
                ) {
                    awaiting?.join()
                    previousAuthenticationJob?.join()
                    // The joined job may itself have just authenticated this same manager (for
                    // example a configure(enabled = false) job that raced setSDK(true) flipping the
                    // flag before it ran). Re-authenticating here would record a second launch.
                    if (manager.authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED) {
                        manager.onEnabled()
                        return@launchOperation
                    }
                    val response = try {
                        manager.authenticate()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Nothing above this job catches, and an escaped exception would crash the host app.
                        DebugLogger.instance.log(LogLevel.ERROR, "Authentication failed: ${e.message}")
                        false
                    }
                    if (response) {
                        manager.start()
                        notificationsManager?.displayAutomaticNotificationsIfNeeded()
                    }
                }
            } ?: run {
                DebugLogger.instance.log(LogLevel.ERROR,"The SDK is not properly configured. Call Grovs.configure(application: Application, apiKey: String) first.")
            }
        } ?: run {
            DebugLogger.instance.log(LogLevel.ERROR,"API Key is invalid. Make sure you've used the right value from the Web interface.")
        }
    }

    private fun handleIntent(intent: Intent?, delayEvents: Boolean, cacheIntent: Boolean = false) {
        val intent = intent ?: defaultIntent
        val manager = grovsManager ?: run {
            DebugLogger.instance.log(LogLevel.ERROR,"The SDK manager is not properly configured. Call Grovs.configure(application: Application, apiKey: String) first.")
            return
        }
        // Consent is taken here rather than inside the lookup, so a disabled SDK never reaches the
        // point where the intent would be marked handled. The intent stays unconsumed and a later
        // explicit onStart/onNewIntent can still resolve it - enabling on its own never replays it.
        val token = grovsContext.consent.tryAcquire(manager.configuration) ?: run {
            DebugLogger.instance.log(LogLevel.INFO, "SDK consent not granted - the intent is left unhandled")
            return
        }
        // Not the launcher's lifecycleScope: a splash screen finishing or a rotation must not
        // cancel a lookup mid-flight, or the link is lost and the intent is already marked handled.
        grovsContext.consent.launchOperation(token, context = grovsContext.serialDispatcher) {
            authenticationJob?.join()
            val result = manager.handleIntent(intent, delayEvents = delayEvents, cacheIntent = cacheIntent)
            result?.let { deeplinkDetails ->
                deeplinkDetails.link?.let { link ->
                    if (handleIntentConflict && (lastLinkMatched == deeplinkDetails.link)) {
                        DebugLogger.instance.log(LogLevel.INFO,"Ignoring double intent handling.")
                        handleIntentConflict = false
                    } else {
                        withContext(Dispatchers.Main) {
                            // Re-checked on the main thread, immediately before the host sees it: a
                            // result that went stale while queued for dispatch is dropped, not
                            // delivered. This is a spontaneous result, so there is nothing to report.
                            if (grovsContext.consent.isCurrent(token)) {
                                openedLinkDetails = deeplinkDetails
                                deeplinkListener?.onDeeplinkReceived(deeplinkDetails)
                            } else {
                                DebugLogger.instance.log(LogLevel.INFO, "SDK consent withdrawn - not delivering the deeplink")
                            }
                        }
                    }
                } ?: run {
                    DebugLogger.instance.log(LogLevel.INFO,"App NOT opened from deeplink.")
                }
            }
            // A lookup superseded by a newer link returns null; it must not forget the link that
            // was just delivered, or the 2-second duplicate-intent guard above stops working.
            result?.let { lastLinkMatched = it.link }
        }
    }

    override fun requireActivity(): Activity? {
        return currentActivityReference?.get()
    }

    override fun requireNotificationsListener(): GrovsNotificationsListener? {
        return grovsNotificationsListener
    }

}
