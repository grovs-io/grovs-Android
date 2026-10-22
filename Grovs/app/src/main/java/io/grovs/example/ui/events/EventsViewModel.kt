package io.grovs.example.ui.events

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.grovs.example.AppGraph
import io.grovs.example.actions.EventActions
import io.grovs.example.actions.TestAction
import io.grovs.example.data.EventForm
import io.grovs.example.data.KeyValue
import io.grovs.example.data.PurchaseForm
import io.grovs.example.data.RECENT_EVENTS
import io.grovs.example.data.RECENT_SCREENS
import io.grovs.example.data.ScreenForm
import io.grovs.example.data.eventNameError
import io.grovs.example.data.tagsError
import kotlinx.coroutines.launch

private val DEFAULT_EVENT_NAMES = listOf("demo_tapped", "signup", "level_up", "share")
private val DEFAULT_SCREEN_NAMES = listOf("Home", "Paywall", "Settings", "Checkout")

class EventsViewModel : ViewModel() {
    private val store = AppGraph.store

    var event by mutableStateOf(store.eventForm)
        private set
    var screen by mutableStateOf(store.screenForm)
        private set
    var purchase by mutableStateOf(store.purchaseForm)
        private set
    var globalTags by mutableStateOf(store.globalTags)
        private set
    var aliases by mutableStateOf(store.screenAliases)
        private set

    val eventError: String? get() = eventNameError(event.name) ?: tagsError(event.tags)
    var eventSuggestions by mutableStateOf(eventNames())
        private set
    var screenSuggestions by mutableStateOf(screenNames())
        private set

    fun updateEvent(change: (EventForm) -> EventForm) { event = change(event) }
    fun updateScreen(change: (ScreenForm) -> ScreenForm) { screen = change(screen) }
    fun updatePurchase(change: (PurchaseForm) -> PurchaseForm) { purchase = change(purchase) }

    fun trackEvent(force: Boolean = false) {
        if (!force && eventError != null) return
        store.eventForm = event
        store.remember(RECENT_EVENTS, event.name)
        eventSuggestions = eventNames()
        run(EventActions.track(event, expectRejected = force && eventError != null))
    }

    fun trackScreen() {
        if (screen.name.isBlank()) return
        store.screenForm = screen
        store.remember(RECENT_SCREENS, screen.name)
        screenSuggestions = screenNames()
        run(EventActions.screen(screen))
    }

    fun logPurchase() {
        val action = EventActions.purchase(purchase, System.currentTimeMillis()) ?: return
        store.purchaseForm = purchase
        run(action)
    }

    fun logPlayPurchase() {
        if (purchase.playJson.isBlank()) return
        store.purchaseForm = purchase
        run(EventActions.playPurchase(purchase.playJson))
    }

    fun applyGlobalTags(tags: List<String>) {
        globalTags = tags
        store.globalTags = tags
        run(EventActions.globalTags(tags))
    }

    fun applyAliases(rows: List<KeyValue>) {
        aliases = rows
        store.screenAliases = rows
        run(EventActions.aliases(rows))
    }

    private fun eventNames() = (store.recent(RECENT_EVENTS) + DEFAULT_EVENT_NAMES).distinct()
    private fun screenNames() = (store.recent(RECENT_SCREENS) + DEFAULT_SCREEN_NAMES).distinct()

    private fun run(action: TestAction) {
        viewModelScope.launch { AppGraph.runner.run(action) }
    }
}
