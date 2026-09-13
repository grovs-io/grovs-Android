package io.grovs.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.grovs.handlers.launchOperation
import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel
import io.grovs.model.notifications.Notification
import io.grovs.service.GrovsService
import io.grovs.utils.LSResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NotificationsMainViewModel(application: Application) : AndroidViewModel(application) {
    // Held here rather than on the fragment so both outlive an Activity recreation. Null only
    // for a fragment restored after process death before the SDK could supply them again.
    var grovsService: GrovsService? = null
    var onDismissed: (() -> Unit)? = null

    private val _notifications = MutableStateFlow(emptyList<Notification>())
    val notifications: StateFlow<List<Notification>> = _notifications.asStateFlow()

    // Controls loading state to show a loading spinner or message
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private var currentPage = 1

    fun loadMoreNotifications() {
        // Set synchronously, before the launch: two taps in the same frame would both get past a
        // guard that the coroutine only raises once it is dispatched.
        if (_isLoading.value) return
        val grovsService = grovsService ?: return
        val consent = grovsService.grovsContext.consent
        val token = consent.tryAcquire(grovsService.configuration) ?: return
        _isLoading.value = true

        val job = consent.launchOperation(token, scope = viewModelScope) {
            val result = grovsService.notifications(currentPage)
            consent.ensureCurrent(token)
            when (result) {
                is LSResult.Success -> {
                    _notifications.value += result.data.notifications ?: emptyList()
                    currentPage += 1
                }
                is LSResult.Error -> {
                    // currentPage stays put so the next call retries this page rather than
                    // skipping past it and leaving a hole in the list.
                    DebugLogger.instance.log(
                        LogLevel.ERROR,
                        "Failed to load messages page $currentPage. ${result.exception.message}"
                    )
                }
            }
        }
        // Also runs when cancellation wins before the coroutine body starts.
        if (job == null) _isLoading.value = false
        else job.invokeOnCompletion { _isLoading.value = false }
    }

    fun markAsRead(notification: Notification) {
        val grovsService = grovsService ?: return
        val consent = grovsService.grovsContext.consent
        val token = consent.tryAcquire(grovsService.configuration) ?: return
        consent.launchOperation(token, scope = viewModelScope) {
            val result = grovsService.markNotificationAsRead(notificationId = notification.id)
            consent.ensureCurrent(token)
            when (result) {
                is LSResult.Success -> {
                    _notifications.value.firstOrNull { it.id == notification.id }?.read = true
                }
                is LSResult.Error -> {}
            }
        }
    }

}