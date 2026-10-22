package io.grovs.example.ui.inapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.grovs.Grovs
import io.grovs.example.AppGraph
import io.grovs.example.actions.ActionResult
import io.grovs.example.actions.EventActions
import io.grovs.example.actions.LogState
import io.grovs.example.actions.LogType
import io.grovs.example.actions.TestAction
import io.grovs.example.actions.callCode
import io.grovs.example.data.RECENT_TRIGGERS
import kotlinx.coroutines.launch

private val DEFAULT_TRIGGERS = listOf("demo_tapped", "onboarding_done", "promo_seen")

class InAppViewModel : ViewModel() {
    private val store = AppGraph.store

    @set:JvmName("assignRunning")
    var running by mutableStateOf(true)
        private set
    var trigger by mutableStateOf(store.triggerEvent)
    @set:JvmName("assignBlockAll")
    var blockAll by mutableStateOf(AppGraph.blockInAppMessages)
        private set
    @set:JvmName("assignDebugLogging")
    var debugLogging by mutableStateOf(store.inAppDebug)
        private set
    @set:JvmName("assignExcluded")
    var excluded by mutableStateOf(store.excludedScreens)
        private set

    var triggerSuggestions by mutableStateOf(triggerNames())
        private set

    fun setRunning(on: Boolean) {
        running = on
        val call = if (on) "resumeInAppMessages" else "pauseInAppMessages"
        run(TestAction(LogType.IN_APP, if (on) "Resume messages" else "Pause messages", callCode(call), repeatable = false) {
            if (on) Grovs.resumeInAppMessages() else Grovs.pauseInAppMessages()
            ActionResult(LogState.OK, if (on) "running" else "paused")
        })
    }

    fun fireTrigger() {
        if (trigger.isBlank()) return
        store.triggerEvent = trigger
        store.remember(RECENT_TRIGGERS, trigger)
        triggerSuggestions = triggerNames()
        run(EventActions.trigger(trigger))
    }

    /** Discarding also leaves the SDK paused. */
    fun discard(dismissCurrent: Boolean) {
        running = false
        val title = if (dismissCurrent) "Close current message" else "Discard waiting messages"
        run(TestAction(LogType.IN_APP, title, callCode("discardInAppMessages", "dismissCurrent" to dismissCurrent), repeatable = false) {
            Grovs.discardInAppMessages(dismissCurrent)
            ActionResult(LogState.OK, "paused")
        })
    }

    fun setBlockAll(on: Boolean) {
        blockAll = on
        AppGraph.blockInAppMessages = on
        AppGraph.log.add(LogType.IN_APP, LogState.NEUTRAL, if (on) "Blocking all messages" else "Messages allowed",
            "shouldShowInAppMessage returns ${!on}")
    }

    fun setDebugLogging(on: Boolean) {
        debugLogging = on
        store.inAppDebug = on
        run(TestAction(LogType.IN_APP, "Debug logging ${if (on) "on" else "off"}", callCode("setInAppDebugLogging", "enabled" to on), repeatable = false) {
            Grovs.setInAppDebugLogging(on)
            ActionResult(LogState.OK, "applied")
        })
    }

    fun setExcluded(screens: List<String>) {
        excluded = screens
        store.excludedScreens = screens
        run(TestAction(LogType.IN_APP, "Excluded screens set", callCode("setInAppExcludedScreens", "screens" to screens), repeatable = false) {
            Grovs.setInAppExcludedScreens(screens)
            ActionResult(LogState.OK, screens.joinToString().ifEmpty { "none" })
        })
    }

    private fun triggerNames() = (store.recent(RECENT_TRIGGERS) + DEFAULT_TRIGGERS).distinct()

    private fun run(action: TestAction) {
        viewModelScope.launch { AppGraph.runner.run(action) }
    }
}
