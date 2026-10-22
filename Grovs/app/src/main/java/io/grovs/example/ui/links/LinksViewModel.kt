package io.grovs.example.ui.links

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.grovs.example.AppGraph
import io.grovs.example.actions.LinkActions
import io.grovs.example.actions.LogState
import io.grovs.example.actions.TestAction
import io.grovs.example.data.LinkForm
import io.grovs.example.data.extractLinkPath
import kotlinx.coroutines.launch

data class LinkResult(val ok: Boolean, val link: String?, val message: String)

class LinksViewModel : ViewModel() {
    private val store = AppGraph.store

    var form by mutableStateOf(store.linkForm)
        private set
    var inspectInput by mutableStateOf("")
    var result by mutableStateOf<LinkResult?>(null)
        private set
    var running by mutableStateOf(false)
        private set

    private var lastAction: TestAction? = null

    fun update(change: (LinkForm) -> LinkForm) {
        form = change(form)
    }

    fun reset() {
        form = LinkForm()
        store.linkForm = form
    }

    fun generate() {
        store.linkForm = form
        run(LinkActions.generate(form, store.callStyle))
    }

    fun runAgain() {
        lastAction?.let(::run)
    }

    fun inspect(input: String = inspectInput) {
        val path = extractLinkPath(input) ?: return
        viewModelScope.launch {
            val outcome = AppGraph.runner.run(LinkActions.details(path, store.callStyle))
            AppGraph.openPanel(outcome.entry.id)
        }
    }

    private fun run(action: TestAction) {
        lastAction = action
        running = true
        viewModelScope.launch {
            val outcome = AppGraph.runner.run(action)
            val ok = outcome.result.state == LogState.OK
            result = LinkResult(
                ok = ok,
                link = outcome.result.value,
                message = if (ok) "${outcome.elapsedMs} ms · ${outcome.result.detail}" else outcome.result.detail,
            )
            running = false
        }
    }
}
