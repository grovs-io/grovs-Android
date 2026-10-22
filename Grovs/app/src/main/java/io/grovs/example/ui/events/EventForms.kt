package io.grovs.example.ui.events

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.grovs.example.data.EventForm
import io.grovs.example.data.PurchaseForm
import io.grovs.example.data.summary
import io.grovs.example.ui.Help
import io.grovs.example.ui.controls.ChipInput
import io.grovs.example.ui.controls.ChipPicker
import io.grovs.example.ui.controls.ChipRow
import io.grovs.example.ui.controls.FoldRow
import io.grovs.example.ui.controls.KeyValueRows
import io.grovs.example.ui.controls.MoneyField
import io.grovs.example.ui.controls.Segment
import io.grovs.example.ui.controls.SuggestField
import io.grovs.example.ui.controls.TextAction
import io.grovs.model.CustomEventRules
import io.grovs.model.events.PaymentEventType

private val CURRENCIES = listOf("USD", "EUR", "GBP", "RON")
private val PRODUCTS = listOf("pro_monthly", "pro_yearly", "coins_100")
private val START_OPTIONS = listOf(0 to "Now", 1 to "1 day ago", 30 to "30 days ago")

@Composable
fun EventFormFields(
    form: EventForm,
    update: ((EventForm) -> EventForm) -> Unit,
    suggestions: List<String>,
    error: String?,
    onTrack: () -> Unit,
    onSendAnyway: () -> Unit = {},
) {
    SuggestField(form.name, { v -> update { it.copy(name = v) } }, suggestions, mono = true, error = error, onImeAction = onTrack)
    if (CustomEventRules.isReserved(form.name)) TextAction("Send anyway", onSendAnyway)
    val summary = listOf(form.properties.summary(), form.tags.joinToString { "tag $it" }).filter { it.isNotBlank() }.joinToString(" · ")
    FoldRow("Properties and tags", summary.ifEmpty { "none" }, info = Help.eventPropertiesAndTags) {
        KeyValueRows(form.properties, { v -> update { it.copy(properties = v) } }, label = "Properties", suggestedKeys = listOf("level", "source"))
        Spacer(Modifier.height(10.dp))
        ChipInput(form.tags, { v -> update { it.copy(tags = v) } }, label = "Tags", placeholder = "Add tag", max = CustomEventRules.MAX_TAGS)
    }
}

@Composable
fun PurchaseFormFields(form: PurchaseForm, update: ((PurchaseForm) -> PurchaseForm) -> Unit) {
    Segment(PaymentEventType.values().toList(), form.type, { v -> update { it.copy(type = v) } }) {
        it.name.lowercase().replaceFirstChar(Char::uppercase)
    }
    Spacer(Modifier.height(10.dp))
    MoneyField(form.amount, { v -> update { it.copy(amount = v) } }, form.currency)
    Spacer(Modifier.height(10.dp))
    ChipPicker(CURRENCIES, form.currency, { v -> update { it.copy(currency = v.uppercase()) } }, label = "Currency", otherPlaceholder = "Code, e.g. JPY")
    Spacer(Modifier.height(10.dp))
    ChipPicker(PRODUCTS, form.productId, { v -> update { it.copy(productId = v) } }, label = "Product", otherPlaceholder = "product id")
    FoldRow("Start date", START_OPTIONS.firstOrNull { it.first == form.startDaysAgo }?.second ?: "${form.startDaysAgo} days ago", info = Help.startDate) {
        ChipRow(START_OPTIONS.map { it.second }, START_OPTIONS.firstOrNull { it.first == form.startDaysAgo }?.second, { label ->
            val days = START_OPTIONS.first { it.second == label }.first
            update { it.copy(startDaysAgo = days) }
        })
    }
}
