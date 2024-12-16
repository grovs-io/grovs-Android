package io.grovs.example

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainViewModel : ViewModel() {
    var incomingLinkCallbackState by mutableStateOf("Invitation link callback")
        private set
    var incomingLinkFlowState by mutableStateOf("Invitation link flow")
        private set
    var unreadNotificationsState by mutableStateOf(0)
        private set

    fun updateCallbackState(newValue: String) {
        incomingLinkCallbackState = newValue
    }

    fun updateFlowState(newValue: String) {
        incomingLinkFlowState = newValue
    }

    fun updateUnreadNotificationsState(newValue: Int) {
        unreadNotificationsState = newValue
    }
}