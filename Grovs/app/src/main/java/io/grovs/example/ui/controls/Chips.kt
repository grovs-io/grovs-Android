package io.grovs.example.ui.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.grovs.example.ui.theme.AppTheme
import io.grovs.example.ui.theme.AppType

@Composable
fun Chip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    dashed: Boolean = false,
    removable: Boolean = false,
    color: Color? = null,
) {
    val c = AppTheme.colors
    val shape = RoundedCornerShape(50)
    Row(
        modifier.clip(shape)
            .background(if (selected) c.accentSoft else c.background)
            .then(if (dashed) Modifier.dashedBorder(c.border, 999.dp) else Modifier.border(1.dp, if (selected) c.accent else c.border, shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = AppType.caption,
            color = color ?: when {
                selected -> c.accent
                dashed -> c.textMuted
                else -> c.text
            },
            maxLines = 1,
        )
        if (removable) Text(" ×", style = AppType.caption, color = c.textFaint)
    }
}

@Composable
fun ChipRow(
    values: List<String>,
    selected: String?,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (String) -> String = { it },
) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { Chip(label(it), { onPick(it) }, selected = it == selected) }
    }
}

@Composable
fun ChipPicker(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    otherLabel: String = "Other…",
    otherPlaceholder: String? = null,
    display: (String) -> String = { it },
) {
    var editingOther by remember { mutableStateOf(false) }
    val isCustom = selected !in options
    Column(modifier.fillMaxWidth()) {
        if (label != null) FieldLabel(label)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { option ->
                Chip(display(option), { editingOther = false; onSelect(option) }, selected = option == selected && !editingOther)
            }
            if (otherPlaceholder != null) {
                Chip(
                    if (isCustom && selected.isNotEmpty()) selected else otherLabel,
                    { editingOther = true },
                    selected = isCustom || editingOther,
                    dashed = !isCustom && !editingOther,
                )
            }
        }
        if (otherPlaceholder != null && (editingOther || isCustom)) {
            AppTextField(if (isCustom) selected else "", onSelect, Modifier.padding(top = 6.dp), placeholder = otherPlaceholder)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipInput(
    values: List<String>,
    onChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "Add",
    max: Int? = null,
    fieldTag: String = "chipInputField",
) {
    val c = AppTheme.colors
    var draft by remember { mutableStateOf("") }
    fun commit(text: String) {
        val added = text.split(',').map { it.trim() }.filter { it.isNotEmpty() && it !in values }.distinct()
        if (added.isNotEmpty()) onChange(values + added)
        draft = ""
    }
    val shape = RoundedCornerShape(8.dp)
    Column(modifier.fillMaxWidth()) {
        if (label != null || max != null) FieldLabel(label ?: "", trailing = max?.let { "${values.size} of $it" })
        FlowRow(
            Modifier.fillMaxWidth().clip(shape).border(1.dp, if (max != null && values.size > max) c.error else c.border, shape)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            values.forEach { value ->
                Chip(value, { onChange(values - value) }, Modifier.padding(vertical = 2.dp), removable = true)
            }
            BasicTextField(
                value = draft,
                onValueChange = { if (it.endsWith(',')) commit(it) else draft = it },
                modifier = Modifier.widthIn(min = 90.dp).padding(horizontal = 4.dp, vertical = 7.dp)
                    .onFocusChanged { if (!it.isFocused && draft.isNotBlank()) commit(draft) }
                    .testTag(fieldTag),
                singleLine = true,
                textStyle = AppType.body.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(autoCorrect = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit(draft) }),
                decorationBox = { inner ->
                    Box {
                        if (draft.isEmpty()) Text(placeholder, style = AppType.body, color = c.textFaint)
                        inner()
                    }
                },
            )
        }
    }
}
