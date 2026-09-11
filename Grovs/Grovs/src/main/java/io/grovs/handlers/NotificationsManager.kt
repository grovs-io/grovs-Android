package io.grovs.handlers

import android.app.Activity
import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.grovs.GrovsNotificationsListener
import io.grovs.fragments.AutoDisplayedNotificationFragment
import io.grovs.fragments.NotificationsMainFragment
import io.grovs.model.notifications.Notification
import io.grovs.service.GrovsService
import io.grovs.utils.LSResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface ActivityProvider {
    fun requireActivity(): Activity?
    fun requireNotificationsListener(): GrovsNotificationsListener?
}

class NotificationsManager(
    val context: Context,
    val grovsContext: GrovsContext,
    apiKey: String,
    val activityProvider: ActivityProvider,
    // Optional custom service implementation for testing (defaults to a real service). Kept as the
    // concrete GrovsService type, not IGrovsService, because the fragments this class constructs
    // (NotificationsMainFragment, AutoDisplayedNotificationFragment) require the concrete type.
    grovsService: GrovsService? = null,
) {
    @get:JvmSynthetic
    internal val configuration: ConsentConfiguration = grovsContext.consent.currentConfiguration

    private val grovsService: GrovsService = grovsService ?: GrovsService(context = context, apiKey = apiKey, grovsContext = grovsContext)

    fun displayAutomaticNotificationsIfNeeded() {
        val consent = grovsContext.consent
        val token = consent.tryAcquire(configuration) ?: return
        val activity = activityProvider.requireActivity() as? FragmentActivity ?: return
        activity.runOnUiThread {
            if (!consent.isCurrent(token) || activity.isDestroyed) return@runOnUiThread
            consent.launchOperation(token, scope = activity.lifecycleScope) {
                val result = grovsService.notificationsToDisplayAutomatically()
                if (result is LSResult.Success) {
                    withContext(Dispatchers.Main.immediate) {
                        for (notification in result.data.notifications ?: emptyList()) {
                            if (!consent.isCurrent(token) || activity.isDestroyed || activity.supportFragmentManager.isStateSaved) break
                            displayAutomaticNotificationFor(notification, activity)
                        }
                    }
                }
            }
        }
    }

    fun displayNotificationsViewController(onDismissed: (()->Unit)?): Boolean {
        if (grovsContext.consent.tryAcquire(configuration) == null) return false
        val activity = activityProvider.requireActivity() as? FragmentActivity
        activity?.let { activity ->
            if (activity.isDestroyed || activity.supportFragmentManager.isStateSaved) return false
            val count = activity.supportFragmentManager.fragments.filterIsInstance<NotificationsMainFragment>().count { it.isVisible }
            if (count != 0) {
                return true
            }

            val dialogFragment = NotificationsMainFragment(grovsService = grovsService)
            dialogFragment.onDialogDismissed = onDismissed
            dialogFragment.show(activity.supportFragmentManager, "NotificationsMainFragment")
            activity.supportFragmentManager.executePendingTransactions()

            return true
        } ?: run {
            return false
        }
    }

    suspend fun numberOfUnreadNotifications(): Int? = try {
        grovsContext.consent.runOperation(configuration) {
            when (val result = grovsService.numberOfUnreadNotifications()) {
                is LSResult.Success -> result.data.numberOfUnreadNotifications
                is LSResult.Error -> null
            }
        }
    } catch (_: ConsentRevokedException) {
        null
    }

    private fun displayAutomaticNotificationFor(
        notification: Notification,
        activity: FragmentActivity,
    ) {
        val alreadyShownFragment = activity.supportFragmentManager.findFragmentByTag(notification.id.toString())
        if (alreadyShownFragment == null) {
            val dialogFragment = AutoDisplayedNotificationFragment.newInstance(notification = notification, grovsService = grovsService)
            dialogFragment.onDialogDismissed = {
                val count = activity.supportFragmentManager.fragments.filterIsInstance<AutoDisplayedNotificationFragment>().count { it.isVisible }
                activityProvider.requireNotificationsListener()?.onAutomaticNotificationClosed(count == 0)
            }
            dialogFragment.show(activity.supportFragmentManager, notification.id.toString())
            activity.supportFragmentManager.executePendingTransactions()
        }
    }

}
