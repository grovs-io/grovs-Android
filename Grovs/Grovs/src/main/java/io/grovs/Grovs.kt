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
import io.grovs.handlers.GrovsContext
import io.grovs.handlers.GrovsManager
import io.grovs.handlers.NavigationScreenTracker
import io.grovs.handlers.NotificationsManager
import io.grovs.handlers.VisibleFragmentResolver
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
         *   never read or sent.
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
        /// - Parameter enabled: `false` stops collection and sending immediately; queued events
        ///   stay on the device. `true` authenticates if the SDK is not authenticated yet (which
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
        if (grovsManager == null) return
        if (!grovsContext.settings.autoTrackScreenViews) return

        val job = GlobalScope.launch(Dispatchers.Main) {
            val lifecycleOwner = activity as? LifecycleOwner
            if (lifecycleOwner != null &&
                !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) {
                return@launch
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
                grovsManager?.autoTrackScreen(screenName, screenClass)
            }
        }
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

            val previousSession = grovsContext.sessionId
            grovsContext.rotateSessionIfNeeded()
            val sessionRotated = grovsContext.sessionId != previousSession

            GlobalScope.launch(grovsContext.serialDispatcher) {
                // ScreenTracker's dedup state is confined to serialDispatcher, so reset it here rather
                // than on the caller's thread. Must precede any screen tracking on this dispatcher.
                if (sessionRotated) {
                    grovsManager?.resetScreenDedup()
                }

                authenticationJob?.join()
                grovsManager?.onAppForegrounded()
            }
        }

        private fun onAppBackgrounded() {
            // App moved to the background
            DebugLogger.instance.log(LogLevel.INFO, "App is in the background")
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
        this.apiKey = apiKey
        this.application = application
        this.grovsContext.settings.useTestEnvironment = useTestEnvironment
        this.grovsContext.settings.baseURL = baseURL
        this.grovsContext.settings.autoTrackScreenViews = autoTrackScreenViews
        this.grovsContext.settings.clipboardDomains = ClipboardHandler.normalizeDomains(clipboardDomains)
        this.grovsContext.settings.sdkEnabled = enabled

        // Stop the previous manager's custom-events flush timer when configure() is called again.
        grovsManager?.close()

        grovsManager = GrovsManager(context = application.applicationContext,
            application = application,
            grovsContext = grovsContext,
            apiKey = apiKey,
            activityProvider = this)

        notificationsManager = NotificationsManager(context = application.applicationContext,
            grovsContext = grovsContext,
            apiKey = apiKey,
            activityProvider = this)

        checkConfiguration()
        // registerActivityLifecycleCallbacks adds to a list, so registering on every configure()
        // would duplicate lifecycle callbacks (and double-report screen views).
        application.unregisterActivityLifecycleCallbacks(applicationLifecycleObserver)
        application.registerActivityLifecycleCallbacks(applicationLifecycleObserver)
    }

    fun setSDK(enabled: Boolean) {
        if (grovsContext.settings.sdkEnabled == enabled) return
        grovsContext.settings.sdkEnabled = enabled
        DebugLogger.instance.log(LogLevel.INFO, "SDK setEnabled to: $enabled")

        if (!enabled) {
            // Stops an authentication that is running or waiting to retry. Nothing else needs
            // stopping: every sender and writer checks the flag before touching disk or network.
            authenticationJob?.cancel()
            return
        }

        val manager = grovsManager ?: return
        if (manager.authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED) {
            GlobalScope.launch(grovsContext.serialDispatcher) { manager.onEnabled() }
        } else {
            checkConfiguration()
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

            withContext(grovsContext.serialDispatcher) {
                authenticationJob?.join()
                val result = manager.generateLink(
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

                withContext(Dispatchers.Main) {
                    when (result) {
                        is LSResult.Success -> {
                            link = result.data.link
                        }
                        is LSResult.Error -> {
                            throw GrovsException(result.exception.message, GrovsErrorCode.LINK_GENERATION_ERROR)
                        }
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

            val scope = (lifecycleOwner?.lifecycleScope ?: GlobalScope)
            scope.launch(grovsContext.serialDispatcher) {
                authenticationJob?.join()
                val result = manager.generateLink(
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

                withContext(Dispatchers.Main) {
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

            withContext(grovsContext.serialDispatcher) {
                authenticationJob?.join()
                val result = manager.linkDetails(path = path)

                withContext(Dispatchers.Main) {
                    when (result) {
                        is LSResult.Success -> {
                            linkDetails = result.data.link
                        }
                        is LSResult.Error -> {
                            throw GrovsException(result.exception.message, GrovsErrorCode.LINK_DETAILS_ERROR)
                        }
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

            val scope = (lifecycleOwner?.lifecycleScope ?: GlobalScope)
            scope.launch(grovsContext.serialDispatcher) {
                authenticationJob?.join()
                val result = manager.linkDetails(path = path)

                withContext(Dispatchers.Main) {
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
        GlobalScope.launch(grovsContext.serialDispatcher) {
            authenticationJob?.join()
            grovsManager?.logInAppPurchase(originalJson = originalJson)
        }
    }

    fun track(name: String, properties: Map<String, Any>? = null, tags: List<String>? = null) {
        GlobalScope.launch(grovsContext.serialDispatcher) {
            grovsManager?.track(name = name, properties = properties, tags = tags)
        }
    }

    fun setGlobalTags(tags: List<String>? = null) {
        // globalTags is confined to serialDispatcher and unsynchronized, so it must be written there.
        GlobalScope.launch(grovsContext.serialDispatcher) {
            grovsManager?.setGlobalTags(tags)
        }
    }

    fun trackScreenView(screenName: String, properties: Map<String, Any>? = null) {
        GlobalScope.launch(grovsContext.serialDispatcher) {
            grovsManager?.trackScreenView(screenName = screenName, properties = properties)
        }
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
        GlobalScope.launch(grovsContext.serialDispatcher) {
            authenticationJob?.join()
            grovsManager?.setScreenAliases(aliases)
        }
    }

    fun logCustomPurchase(type: PaymentEventType, priceInCents: Int, currency: String, productId: String, startDate: InstantCompat? = InstantCompat.now()) {
        GlobalScope.launch(grovsContext.serialDispatcher) {
            authenticationJob?.join()
            grovsManager?.logCustomPurchase(type = type,
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

        authenticationJob?.join()

        return notificationsManager?.numberOfUnreadNotifications()
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

        val scope = (lifecycleOwner?.lifecycleScope ?: GlobalScope)
        scope.launch(grovsContext.serialDispatcher) {
            authenticationJob?.join()
            val result = notificationsManager?.numberOfUnreadNotifications()

            withContext(Dispatchers.Main) {
                onResult?.invoke(result)
            }
        }

    }

    private fun checkConfiguration() {
        instance.apiKey?.let { apiKey ->
            grovsManager?.let { manager ->
                val previousAuthenticationJob = authenticationJob
                authenticationJob = GlobalScope.launch(grovsContext.serialDispatcher) {
                    previousAuthenticationJob?.join()
                    // The joined job may itself have just authenticated this same manager (for
                    // example a configure(enabled = false) job that raced setSDK(true) flipping the
                    // flag before it ran). Re-authenticating here would record a second launch.
                    if (manager.authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED) {
                        return@launch
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
        grovsManager?.let { grovsManager ->
            // Not the launcher's lifecycleScope: a splash screen finishing or a rotation must not
            // cancel a lookup mid-flight, or the link is lost and the intent is already marked handled.
            GlobalScope.launch(grovsContext.serialDispatcher) {
                authenticationJob?.join()
                val result = grovsManager.handleIntent(intent, delayEvents = delayEvents, cacheIntent = cacheIntent)
                result?.let { deeplinkDetails ->
                    deeplinkDetails.link?.let { link ->
                        if (handleIntentConflict && (lastLinkMatched == deeplinkDetails.link)) {
                            DebugLogger.instance.log(LogLevel.INFO,"Ignoring double intent handling.")
                            handleIntentConflict = false
                        } else {
                            withContext(Dispatchers.Main) {
                                openedLinkDetails = deeplinkDetails
                                deeplinkListener?.onDeeplinkReceived(deeplinkDetails)
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
        } ?: run {
            DebugLogger.instance.log(LogLevel.ERROR,"The SDK manager is not properly configured. Call Grovs.configure(application: Application, apiKey: String) first.")
        }
    }

    override fun requireActivity(): Activity? {
        return currentActivityReference?.get()
    }

    override fun requireNotificationsListener(): GrovsNotificationsListener? {
        return grovsNotificationsListener
    }

}
