package io.grovs.example

import android.content.Context
import io.grovs.example.actions.TestAction
import io.grovs.example.actions.TestLog
import io.grovs.example.actions.TestRunner
import io.grovs.example.data.Profile
import io.grovs.example.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class PanelState(val open: Boolean = false, val entryId: Long? = null)

/** App-wide objects, created once in [MainApplication]. */
object AppGraph {
    lateinit var store: SettingsStore
        private set
    lateinit var log: TestLog
        private set
    lateinit var runner: TestRunner
        private set
    lateinit var startedProfile: Profile
        private set

    val scope: CoroutineScope = MainScope()
    val pushToken = MutableStateFlow<String?>(null)
    val sdkEnabled = MutableStateFlow(true)
    val panel = MutableStateFlow(PanelState())

    @Volatile
    var blockInAppMessages = false

    fun init(context: Context) {
        store = SettingsStore(context)
        log = TestLog(history = store)
        runner = TestRunner(log)
        startedProfile = store.activeProfile
        sdkEnabled.value = startedProfile.startEnabled
    }

    /** Used when the stored profile cannot configure the SDK, so it is not tried again on the next launch. */
    fun fallBackToDefaultProfile(): Profile {
        startedProfile = store.resetToDefaultProfile()
        sdkEnabled.value = startedProfile.startEnabled
        return startedProfile
    }

    fun rerun(action: TestAction) {
        scope.launch { runner.run(action) }
    }

    fun openPanel(entryId: Long? = null) {
        panel.value = PanelState(open = true, entryId = entryId)
        log.markSeen()
    }

    /** Errors that arrived while the panel was open have been seen. */
    fun closePanel() {
        panel.value = PanelState()
        log.markSeen()
    }
}
