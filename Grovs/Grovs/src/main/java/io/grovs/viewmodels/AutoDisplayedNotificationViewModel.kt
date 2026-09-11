package io.grovs.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.grovs.handlers.launchOperation
import io.grovs.model.notifications.Notification
import io.grovs.service.GrovsService

class AutoDisplayedNotificationViewModel(application: Application) : AndroidViewModel(application) {
    lateinit var grovsService: GrovsService

    fun markAsRead(notification: Notification) {
        val consent = grovsService.grovsContext.consent
        val token = consent.tryAcquire(grovsService.configuration) ?: return
        consent.launchOperation(token, scope = viewModelScope) {
            grovsService.markNotificationAsRead(notificationId = notification.id)
        }
    }

}