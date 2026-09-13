package io.grovs.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.grovs.handlers.launchOperation
import io.grovs.model.notifications.Notification
import io.grovs.service.GrovsService

class AutoDisplayedNotificationViewModel(application: Application) : AndroidViewModel(application) {
    // Held here rather than on the fragment so both outlive an Activity recreation. Null only
    // for a fragment restored after process death before the SDK could supply them again.
    var grovsService: GrovsService? = null
    var onDismissed: (() -> Unit)? = null

    fun markAsRead(notification: Notification) {
        val grovsService = grovsService ?: return
        val consent = grovsService.grovsContext.consent
        val token = consent.tryAcquire(grovsService.configuration) ?: return
        consent.launchOperation(token, scope = viewModelScope) {
            grovsService.markNotificationAsRead(notificationId = notification.id)
        }
    }

}
