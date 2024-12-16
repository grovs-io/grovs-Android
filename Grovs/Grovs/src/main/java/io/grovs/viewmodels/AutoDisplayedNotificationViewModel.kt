package io.grovs.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.grovs.model.notifications.Notification
import io.grovs.service.GrovsService
import kotlinx.coroutines.launch

class AutoDisplayedNotificationViewModel(application: Application) : AndroidViewModel(application) {
    lateinit var grovsService: GrovsService

    fun markAsRead(notification: Notification) {
        viewModelScope.launch {
            val result = grovsService.markNotificationAsRead(notificationId = notification.id)
        }
    }

}