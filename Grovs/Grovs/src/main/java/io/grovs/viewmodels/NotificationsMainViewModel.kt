package io.grovs.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.grovs.model.notifications.Notification
import io.grovs.service.GrovsService
import io.grovs.utils.LSResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NotificationsMainViewModel(application: Application) : AndroidViewModel(application) {
    lateinit var grovsService: GrovsService

    private val _notifications = MutableStateFlow(emptyList<Notification>())
    val notifications: StateFlow<List<Notification>> = _notifications.asStateFlow()

    // Controls loading state to show a loading spinner or message
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private var currentPage = 1

    fun loadMoreNotifications() {
        viewModelScope.launch {
            _isLoading.value = true
            val result = grovsService.notifications(currentPage)
            when (result) {
                is LSResult.Success -> {
                    _notifications.value += result.data.notifications ?: emptyList()
                    currentPage += 1
                    _isLoading.value = false
                }
                is LSResult.Error -> {}
            }
        }
    }

    fun markAsRead(notification: Notification) {
        viewModelScope.launch {
            val result = grovsService.markNotificationAsRead(notificationId = notification.id)
            when (result) {
                is LSResult.Success -> {
                    _notifications.value.firstOrNull { it.id == notification.id }?.read = true
                }
                is LSResult.Error -> {}
            }
        }
    }

}