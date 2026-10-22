package io.grovs.example.ui.home

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.grovs.Grovs
import io.grovs.example.AppGraph
import io.grovs.example.actions.ActionResult
import io.grovs.example.actions.LogState
import io.grovs.example.actions.LogType
import io.grovs.example.actions.TestAction
import io.grovs.example.actions.callCode
import io.grovs.example.data.Identity
import io.grovs.example.data.toValueMap
import kotlinx.coroutines.launch

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val store = AppGraph.store

    var notificationsAllowed by mutableStateOf(checkPermission())
        private set
    var identity by mutableStateOf(store.identity)
        private set
    var editingIdentity by mutableStateOf(false)
        private set
    var draft by mutableStateOf(identity)
        private set

    fun refreshPermission() {
        notificationsAllowed = checkPermission()
    }

    fun setSdkEnabled(on: Boolean) {
        AppGraph.sdkEnabled.value = on
        run(TestAction(LogType.SDK, if (on) "SDK enabled" else "SDK disabled", callCode("setSDK", "enabled" to on), repeatable = false) {
            Grovs.setSDK(on)
            ActionResult(LogState.OK, if (on) "sending" else "holding events")
        })
    }

    fun startEditing() {
        draft = identity
        editingIdentity = true
    }

    fun cancelEditing() {
        editingIdentity = false
    }

    fun updateDraft(change: (Identity) -> Identity) {
        draft = change(draft)
    }

    fun saveIdentity() {
        val saved = draft
        identity = saved
        store.identity = saved
        editingIdentity = false
        val attributes = saved.attributes.toValueMap()
        run(TestAction(LogType.SDK, "Identity saved", callCode("identity", "identifier" to saved.identifier, "attributes" to attributes), repeatable = false) {
            Grovs.identifier = saved.identifier
            Grovs.attributes = attributes
            ActionResult(LogState.OK, saved.identifier ?: "no identifier")
        })
    }

    private fun checkPermission(): Boolean {
        val context = getApplication<Application>()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    private fun run(action: TestAction) {
        viewModelScope.launch { AppGraph.runner.run(action) }
    }
}
