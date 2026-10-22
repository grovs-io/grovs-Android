package io.grovs.example.ui.controls

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.grovs.example.data.KeyValue
import io.grovs.example.data.next
import io.grovs.example.data.parseKeyValues
import io.grovs.example.data.toJson
import io.grovs.example.ui.theme.AppTheme
import io.grovs.example.ui.theme.AppType
import org.json.JSONException

@Composable
fun KeyValueRows(
    rows: List<KeyValue>,
    onChange: (List<KeyValue>) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    info: Info? = null,
    suggestedKeys: List<String> = emptyList(),
    textOnly: Boolean = false,
) {
    val c = AppTheme.colors
    val sheets = LocalSheetHost.current
    fun replace(index: Int, row: KeyValue) = onChange(rows.toMutableList().also { it[index] = row })
    Column(modifier.fillMaxWidth()) {
        if (label != null) {
            FieldLabel(
                label,
                info = info,
                action = if (textOnly) null else "Paste JSON",
                onAction = { sheets.show { JsonPasteSheet(rows) { onChange(it); sheets.hide() } } },
            )
        }
        rows.forEachIndexed { i, row ->
            Row(Modifier.padding(bottom = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                AppTextField(row.key, { replace(i, row.copy(key = it)) }, Modifier.weight(1f).testTag("kvKey$i"), placeholder = "key", imeAction = ImeAction.Next)
                AppTextField(
                    row.raw, { replace(i, row.copy(raw = it)) }, Modifier.weight(1.2f).testTag("kvValue$i"),
                    placeholder = "value",
                    trailing = if (textOnly) null else {
                        {
                            Text(
                                row.resolvedType.badge,
                                style = AppType.monoSmall,
                                color = c.textFaint,
                                modifier = Modifier.clickable { replace(i, row.copy(type = row.resolvedType.next())) },
                            )
                        }
                    },
                )
                TextAction("×", { onChange(rows.filterIndexed { j, _ -> j != i }) }, color = c.textFaint)
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("+ Add", { onChange(rows + KeyValue("", "")) }, dashed = true)
            suggestedKeys.filter { key -> rows.none { it.key == key } }.forEach { key ->
                Chip("+ $key", { onChange(rows + KeyValue(key, "")) })
            }
        }
    }
}

@Composable
private fun JsonPasteSheet(rows: List<KeyValue>, onApply: (List<KeyValue>) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf(if (rows.isEmpty()) "{}" else rows.toJson()) }
    var error by remember { mutableStateOf<String?>(null) }
    Text("Paste JSON", style = AppType.heading, color = AppTheme.colors.text, modifier = Modifier.padding(bottom = 8.dp))
    AppTextField(text, { text = it; error = null }, Modifier.heightIn(min = 160.dp), mono = true, singleLine = false, imeAction = ImeAction.Default)
    HelpText(error ?: "An object, e.g. {\"level\": 3}", isError = error != null)
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppButton("Paste", { clipboard.getText()?.text?.let { text = it } }, Modifier.weight(1f), style = ButtonStyle.OUTLINE)
        AppButton("Apply", {
            try {
                onApply(parseKeyValues(text))
            } catch (e: JSONException) {
                error = "Not valid JSON"
            }
        }, Modifier.weight(1f))
    }
}
