package io.grovs.example.ui.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.grovs.example.data.currencySymbol
import io.grovs.example.data.extractLinkPath
import io.grovs.example.data.isWebUrl
import io.grovs.example.data.parseAmountToCents
import io.grovs.example.ui.theme.AppTheme
import io.grovs.example.ui.theme.AppType

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    mono: Boolean = false,
    error: Boolean = false,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    textStyle: TextStyle? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = AppTheme.colors
    val focusManager = LocalFocusManager.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border = when {
        error -> c.error
        focused -> c.accent
        else -> c.border
    }
    val style = (textStyle ?: if (mono) AppType.mono else AppType.body).copy(color = c.text)
    val shape = RoundedCornerShape(8.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().revealWhenFocused(),
        textStyle = style,
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(autoCorrect = false, keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onNext = { focusManager.moveFocus(FocusDirection.Next) },
            onDone = { focusManager.clearFocus(); onImeAction() },
            onGo = { onImeAction() },
            onSearch = { onImeAction() },
        ),
        interactionSource = interaction,
        cursorBrush = SolidColor(c.accent),
        decorationBox = { inner ->
            Row(
                Modifier.heightIn(min = FIELD_HEIGHT).clip(shape).background(c.background).border(1.dp, border, shape)
                    .padding(horizontal = 10.dp, vertical = if (singleLine) 0.dp else 10.dp),
                verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                leading?.invoke()
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = style, color = c.textFaint, maxLines = 1)
                    inner()
                }
                trailing?.invoke(this)
            }
        },
    )
}

@Composable
fun SuggestField(
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<String>,
    modifier: Modifier = Modifier,
    label: String? = null,
    info: Info? = null,
    placeholder: String = "",
    error: String? = null,
    mono: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        if (label != null) FieldLabel(label, info = info)
        AppTextField(
            value, onValueChange,
            placeholder = placeholder, mono = mono, error = error != null,
            imeAction = imeAction, onImeAction = onImeAction,
            trailing = if (value.isEmpty()) null else { { TextAction("✕", { onValueChange("") }, color = AppTheme.colors.textFaint) } },
        )
        if (error != null) HelpText(error, isError = true)
        if (suggestions.isNotEmpty()) ChipRow(suggestions.distinct(), value, onValueChange, Modifier.padding(top = 6.dp))
    }
}

@Composable
fun LinkField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    info: Info? = null,
    placeholder: String = "https://",
    showPath: Boolean = false,
    onDone: () -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    Column(modifier.fillMaxWidth()) {
        if (label != null) FieldLabel(label, info = info)
        AppTextField(
            value, onValueChange,
            placeholder = placeholder, mono = true, keyboardType = KeyboardType.Uri, onImeAction = onDone,
            trailing = {
                TextAction("Paste", {
                    clipboard.getText()?.text?.trim()?.let {
                        onValueChange(it)
                        if (showPath) onDone()
                    }
                })
            },
        )
        if (value.isNotBlank()) {
            if (showPath) {
                val path = extractLinkPath(value)
                HelpText(path?.let { "Path: $it" } ?: "No path found", isError = path == null)
            } else if (!isWebUrl(value)) {
                HelpText("Must start with http:// or https://", isError = true)
            }
        }
    }
}

@Composable
fun MoneyField(amount: String, onAmountChange: (String) -> Unit, currency: String, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    val cents = parseAmountToCents(amount)
    Column(modifier.fillMaxWidth()) {
        AppTextField(
            amount,
            { text -> onAmountChange(text.filter { it.isDigit() || it == '.' || it == ',' }) },
            keyboardType = KeyboardType.Decimal,
            error = cents == null,
            textStyle = AppType.amount,
            leading = { Text(currencySymbol(currency), style = AppType.amount, color = c.textMuted) },
        )
        HelpText(if (cents != null) "Sent as $cents cents" else "Enter an amount like 4.99", isError = cents == null)
    }
}
