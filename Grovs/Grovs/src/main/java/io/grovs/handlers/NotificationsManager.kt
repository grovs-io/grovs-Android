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
import kotlinx.coroutines.launch

interface ActivityProvider {
    fun requireActivity(): Activity?
    fun requireNotificationsListener(): GrovsNotificationsListener?
}

class NotificationsManager(val context: Context, val grovsContext: GrovsContext, apiKey: String, val activityProvider: ActivityProvider) {
    private val grovsService = GrovsService(context = context, apiKey = apiKey, grovsContext = grovsContext)

    fun displayAutomaticNotificationsIfNeeded() {
        val activity = activityProvider.requireActivity() as? FragmentActivity
        activity?.lifecycleScope?.launch {
            val result = grovsService.notificationsToDisplayAutomatically()
            when (result) {
                is LSResult.Success -> {
                    for (notification in result.data.notifications ?: emptyList()) {
                        displayAutomaticNotificationFor(notification = notification)
                    }
                }
                is LSResult.Error -> {}
            }
        }

//        val activity = activityProvider.requireActivity() as? FragmentActivity
//        activity?.lifecycleScope?.launch {
//            val notification = Notification(
//                123,
//                "Test not",
//                Instant.now(),
//                "Test sub",
//                autoDisplay = true,
//                "https:google.ro",
//                read = false
//            )
//            displayAutomaticNotificationFor(notification)
//
//            val notification2 = Notification(
//                1234,
//                "Test not",
//                Instant.now(),
//                "Test sub",
//                autoDisplay = true,
//                "https:google.ro",
//                read = false
//            )
//            displayAutomaticNotificationFor(notification2)
//        }
    }

    fun displayNotificationsViewController(onDismissed: (()->Unit)?): Boolean {
        val activity = activityProvider.requireActivity() as? FragmentActivity
        activity?.let { activity ->
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

    suspend fun numberOfUnreadNotifications(): Int? {
        val result = grovsService.numberOfUnreadNotifications()
        when (result) {
            is LSResult.Success -> {
                return result.data.numberOfUnreadNotifications
            }
            is LSResult.Error -> {
                return null
            }
        }
    }

    private fun displayAutomaticNotificationFor(notification: Notification) {
        val activity = activityProvider.requireActivity() as? FragmentActivity
        activity?.let { activity ->
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

}