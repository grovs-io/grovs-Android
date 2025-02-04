package io.grovs

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.grovs.handlers.ActivityProvider
import io.grovs.handlers.GrovsContext
import io.grovs.handlers.GrovsManager
import io.grovs.handlers.NotificationsManager
import io.grovs.model.DebugLogger
import io.grovs.model.DeeplinkDetails
import io.grovs.model.LogLevel
import io.grovs.model.exceptions.GrovsErrorCode
import io.grovs.model.exceptions.GrovsException
import io.grovs.utils.FlowObservable
import io.grovs.utils.LSResult
import io.grovs.utils.flowDelegate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable
import java.lang.ref.WeakReference

fun interface GrovsDeeplinkListener {
    fun onDeeplinkReceived(link:String, data:Map<String, Object>?)
}

fun interface GrovsLinkGenerationListener {
    fun onLinkGenerated(link:String?, error: GrovsException?)
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

        /// Configures Grovs with the API key from the web console
        fun configure(application: Application, apiKey: String, useTestEnvironment: Boolean) {
            instance.configure(application, apiKey, useTestEnvironment = useTestEnvironment)
        }

        /// Disables the Grovs SDK.
        /// - Parameter enabled: The log level to set.
        /// Default is true.
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
        suspend fun generateLink(title: String? = null,
                                 subtitle: String? = null,
                                 imageURL: String? = null,
                                 data: Map<String, Serializable>? = null,
                                 tags: List<String>? = null): String {
            return instance.generateLink(title, subtitle, imageURL, data, tags)
        }

        /// Generates a link.
        ///
        /// - Parameters:
        ///   - title: The title of the link.
        ///   - subtitle: The subtitle of the link.
        ///   - imageURL: The URL of the image associated with the link.
        ///   - data: Additional data for the link.
        ///   - tags: Tags for the link.
        ///   - completion: A closure to be executed after generating the link.
        fun generateLink(title: String? = null,
                         subtitle: String? = null,
                         imageURL: String? = null,
                         data: Map<String, Serializable>? = null,
                         tags: List<String>? = null,
                         lifecycleOwner: LifecycleOwner? = null,
                         listener: GrovsLinkGenerationListener
        ) {
            instance.generateLink(title, subtitle, imageURL, data, tags, lifecycleOwner, listener)
        }

        /// This needs to be called on the launcher activity onStart() to allow the SDK to handle incoming links
        fun onStart() {
            instance.onStart()
        }

        /// This needs to be called on the launcher activity onNewIntent() to allow the SDK to handle incoming links
        fun onNewIntent(intent: Intent?) {
            instance.onNewIntent(intent)
        }

        /// Register a listener to receive the link and data from which the app was opened.
        ///
        /// - Parameters:
        ///   - launcherActivity: The launcher activity.
        ///   - listener: A listener to receive the link and data from which the app was opened.
        fun setOnDeeplinkReceivedListener(launcherActivity: Activity, listener: GrovsDeeplinkListener) {
            instance.setOnDeeplinkReceivedListener(launcherActivity, listener)
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
            if ((field != null) && (grovsManager?.authenticated == true)) {
                notificationsManager?.displayAutomaticNotificationsIfNeeded()
            }
        }

    private var grovsContext = GrovsContext()

    private var authenticationJob: Job? = null

    private val applicationLifecycleObserver: Application.ActivityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        private var numStarted = 0

        override fun onActivityCreated(p0: Activity, p1: Bundle?) {}
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
        }
        override fun onActivityPaused(activity: Activity) {
            if (currentActivityReference?.get() == activity) currentActivityReference = null
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
        }

        private fun onAppForegrounded() {
            // App moved to the foreground
            DebugLogger.instance.log(LogLevel.INFO, "App is in the foreground")

            GlobalScope.launch(grovsContext.serialDispatcher) {
                authenticationJob?.join()
                grovsManager?.onAppForegrounded()
            }
        }

        private fun onAppBackgrounded() {
            // App moved to the background
            DebugLogger.instance.log(LogLevel.INFO, "App is in the background")
            grovsManager?.onAppBackgrounded()
        }
    }

    fun configure(application: Application, apiKey: String, useTestEnvironment: Boolean) {
        this.apiKey = apiKey
        this.application = application
        this.grovsContext.settings.useTestEnvironment = useTestEnvironment

        grovsManager = GrovsManager(context = application.applicationContext,
            application = application,
            grovsContext = grovsContext,
            apiKey = apiKey)

        notificationsManager = NotificationsManager(context = application.applicationContext,
            grovsContext = grovsContext,
            apiKey = apiKey,
            activityProvider = this)

        checkConfiguration()
        application.registerActivityLifecycleCallbacks(applicationLifecycleObserver)
    }

    fun setSDK(enabled: Boolean) {
        grovsContext.settings.sdkEnabled = enabled
        grovsManager?.setEnabled(enabled)
    }

    fun setDebug(level: LogLevel) {
        grovsContext.settings.debugLevel = level
    }

    suspend fun generateLink(title: String? = null,
                     subtitle: String? = null,
                     imageURL: String? = null,
                     data: Map<String, Serializable>? = null,
                     tags: List<String>? = null): String {
        var link: String? = null
        grovsManager?.let { manager ->
            withContext(grovsContext.serialDispatcher) {
                authenticationJob?.join()
                val result = manager.generateLink(
                    title = title,
                    subtitle = subtitle,
                    imageURL = imageURL,
                    data = data,
                    tags = tags
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
                     lifecycleOwner: LifecycleOwner? = null,
                     listener: GrovsLinkGenerationListener
    ) {
        grovsManager?.let { manager ->
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
                    tags = tags
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
            DebugLogger.instance.log(LogLevel.ERROR,"The SDK is not properly configured. Call Grovs.configure(application: Application, apiKey: String) first.")
        }
    }

    fun onStart() {
        handleIntent(launcherActivityReference?.get()?.intent, delayEvents = true)
    }

    fun onNewIntent(intent: Intent?) {
        handleIntent(intent, delayEvents = false)
    }

    fun setOnDeeplinkReceivedListener(launcherActivity: Activity, listener: GrovsDeeplinkListener) {
        launcherActivityReference = WeakReference(launcherActivity)
        deeplinkListener = listener
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
        authenticationJob?.join()

        return notificationsManager?.numberOfUnreadNotifications()
    }

    fun numberOfUnreadMessages(lifecycleOwner: LifecycleOwner? = null, onResult: ((Int?)->Unit)?) {
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
                    val response = manager.authenticate()
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

    private fun handleIntent(intent: Intent?, delayEvents: Boolean) {
        intent?.let { intent ->
            grovsManager?.let { grovsManager ->
                (launcherActivityReference?.get() as? LifecycleOwner)?.let { lifecycleOwner ->
                    lifecycleOwner.lifecycleScope.launch(grovsContext.serialDispatcher) {
                        authenticationJob?.join()
                            val result = grovsManager.handleIntent(intent, delayEvents = delayEvents)
                            result?.let { deeplinkDetails ->
                                deeplinkDetails.link?.let { link ->
                                    withContext(Dispatchers.Main) {
                                        openedLinkDetails = deeplinkDetails
                                        deeplinkListener?.onDeeplinkReceived(link, deeplinkDetails.data)
                                    }
                                } ?: run {
                                    DebugLogger.instance.log(LogLevel.INFO,"App NOT opened from deeplink.")
                                }
                            }
                    }
                } ?: run {
                    DebugLogger.instance.log(LogLevel.ERROR,"The SDK is not properly configured. Call Grovs.configure(application: Application, apiKey: String) first.")
                }
            } ?: run {
                DebugLogger.instance.log(LogLevel.ERROR,"The SDK is not properly configured. Call Grovs.configure(application: Application, apiKey: String) first.")
            }
        }
    }

    override fun requireActivity(): Activity? {
        return currentActivityReference?.get()
    }

    override fun requireNotificationsListener(): GrovsNotificationsListener? {
        return grovsNotificationsListener
    }

}