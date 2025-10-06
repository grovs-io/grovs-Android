package io.grovs.example

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import io.grovs.Grovs
import io.grovs.utils.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {
    var incomingLinkCallbackState by mutableStateOf("Invitation link callback")
        private set
    var incomingLinkFlowState by mutableStateOf("Invitation link flow")
        private set
    var unreadNotificationsState by mutableStateOf(0)
        private set

    init {
        viewModelScope.launch {
            Grovs.Companion::openedLinkDetails.flow.collect { deeplinkDetails ->
                val message = "Got link from flow: ${deeplinkDetails?.link} payload: ${deeplinkDetails?.data}"
                Log.d("MainActivity", message)

                updateFlowState(message)
            }
        }
    }

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