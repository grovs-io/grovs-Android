package io.grovs.example.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.grovs.Grovs
import io.grovs.example.AppGraph
import io.grovs.example.actions.LogState
import io.grovs.example.actions.LogType
import io.grovs.example.actions.callCode
import io.grovs.example.data.CallStyle
import io.grovs.example.data.Profile
import io.grovs.example.data.restartChanges
import io.grovs.model.LogLevel
import java.util.UUID

class SettingsViewModel : ViewModel() {
    private val store = AppGraph.store

    var profiles by mutableStateOf(store.profiles)
        private set
    var activeId by mutableStateOf(store.activeProfileId)
        private set
    var debugLevel by mutableStateOf(store.debugLevel)
        private set
    var callStyle by mutableStateOf(store.callStyle)
        private set

    val active: Profile get() = profiles.firstOrNull { it.id == activeId } ?: profiles.first()
    val restartChanges: List<String> get() = restartChanges(AppGraph.startedProfile, active)

    fun select(id: String) {
        activeId = id
        store.activeProfileId = id
    }

    fun updateActive(change: (Profile) -> Profile) {
        val id = active.id
        profiles = profiles.map { if (it.id == id) change(it) else it }
        store.profiles = profiles
    }

    fun addProfile() {
        val copy = active.copy(id = UUID.randomUUID().toString(), name = "${active.name} copy")
        profiles = profiles + copy
        store.profiles = profiles
        select(copy.id)
    }

    fun deleteActive() {
        if (profiles.size <= 1) return
        val remaining = profiles.filterNot { it.id == active.id }
        profiles = remaining
        store.profiles = remaining
        select(remaining.first().id)
    }

    fun changeDebugLevel(level: LogLevel) {
        debugLevel = level
        store.debugLevel = level
        Grovs.setDebug(level)
        AppGraph.log.add(LogType.SDK, LogState.OK, "Debug level ${level.name}", "", callCode("setDebug", "level" to level.name))
    }

    fun changeCallStyle(style: CallStyle) {
        callStyle = style
        store.callStyle = style
    }
}
