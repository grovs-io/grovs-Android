package io.grovs.example.ui.controls

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.grovs.example.actions.LogState
import io.grovs.example.data.TriState
import io.grovs.example.data.middleEllipsis
import io.grovs.example.ui.theme.AppTheme
import io.grovs.example.ui.theme.AppType

@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = AppType.title, color = AppTheme.colors.text, modifier = Modifier.weight(1f))
        if (action != null) TextAction(action, onAction)
    }
}

@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
    trailing: String? = null,
    info: Info? = null,
) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LabelWithInfo(text, AppType.label, AppTheme.colors.textMuted, info)
        if (trailing != null) Text(trailing, style = AppType.caption, color = AppTheme.colors.textFaint)
        if (action != null) TextAction(action, onAction)
    }
}

@Composable
fun FieldLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    action: String? = null,
    onAction: () -> Unit = {},
    info: Info? = null,
) {
    val c = AppTheme.colors
    Row(modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LabelWithInfo(text, AppType.label, c.textMuted, info)
        if (trailing != null) Text(trailing, style = AppType.caption, color = c.textMuted)
        if (action != null) TextAction(action, onAction)
    }
}

/** A label that takes the free width of its row, with an optional "?" right after the text. */
@Composable
private fun RowScope.LabelWithInfo(text: String, style: TextStyle, color: Color, info: Info?) {
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = style, color = color, modifier = Modifier.weight(1f, fill = false))
        if (info != null) InfoButton(info)
    }
}

@Composable
fun HelpText(text: String, isError: Boolean = false, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Text(text, style = AppType.caption, color = if (isError) c.error else c.textMuted, modifier = modifier.padding(top = 4.dp))
}

@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color? = null, enabled: Boolean = true) {
    Text(
        text,
        style = AppType.label,
        color = if (enabled) color ?: AppTheme.colors.accent else AppTheme.colors.textMuted,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(4.dp),
    )
}

@Composable
fun SettingRow(
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
    info: Info? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = AppTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .heightIn(min = 44.dp)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LabelWithInfo(label, AppType.body, c.text, info)
            trailing()
        }
        if (showDivider) Divider(color = c.divider, thickness = 1.dp)
    }
}

@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    showDivider: Boolean = true,
    info: Info? = null,
) {
    val c = AppTheme.colors
    SettingRow(label, modifier, onClick = { onCheckedChange(!checked) }, showDivider = showDivider, info = info) {
        if (supporting != null) Text(supporting, style = AppType.caption, color = c.textMuted)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = c.accent,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = c.neutralDot,
                uncheckedThumbColor = Color.White,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
fun <T> Segment(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    label: (T) -> String = { it.toString() },
) {
    val c = AppTheme.colors
    Row(
        modifier.clip(RoundedCornerShape(9.dp)).background(c.chipFill).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            Box(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (on) c.background else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(option) }
                    .padding(vertical = if (compact) 4.dp else 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = AppType.caption,
                    fontWeight = FontWeight.Medium,
                    color = if (on) c.text else c.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun TriStateRow(label: String, value: TriState, info: Info? = null, onChange: (TriState) -> Unit) {
    SettingRow(label, info = info) {
        Segment(TriState.values().toList(), value, onChange, Modifier.width(170.dp), compact = true) { it.label }
    }
}

@Composable
fun LogState.color(): Color {
    val c = AppTheme.colors
    return when (this) {
        LogState.OK -> c.ok
        LogState.WARNING -> c.warning
        LogState.ERROR -> c.error
        LogState.NEUTRAL -> c.neutralDot
    }
}

@Composable
fun LogState.softColor(): Color {
    val c = AppTheme.colors
    return when (this) {
        LogState.OK -> c.okSoft
        LogState.WARNING -> c.warningSoft
        LogState.ERROR -> c.errorSoft
        LogState.NEUTRAL -> c.chipFill
    }
}

@Composable
fun StatusDot(state: LogState, modifier: Modifier = Modifier) {
    Box(modifier.size(7.dp).clip(CircleShape).background(state.color()))
}

@Composable
fun StatusPill(text: String, state: LogState) {
    Text(
        text,
        style = AppType.caption,
        fontWeight = FontWeight.Medium,
        color = if (state == LogState.NEUTRAL) AppTheme.colors.textMuted else state.color(),
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(state.softColor()).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

enum class ButtonStyle { PRIMARY, OUTLINE }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.PRIMARY,
    enabled: Boolean = true,
    small: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val c = AppTheme.colors
    val shape = RoundedCornerShape(if (small) 8.dp else 9.dp)
    val background = when {
        !enabled -> c.neutralDot
        style == ButtonStyle.PRIMARY -> c.accent
        else -> c.background
    }
    val foreground = if (!enabled || style == ButtonStyle.PRIMARY) Color.White else c.text
    Box(
        modifier
            .heightIn(min = if (small) 34.dp else 40.dp)
            .clip(shape)
            .background(background)
            .then(if (enabled && style == ButtonStyle.OUTLINE) Modifier.border(1.dp, c.border, shape) else Modifier)
            // Long press stays available on a disabled button so its saved values can still be edited.
            .combinedClickable(enabled = enabled || onLongClick != null, role = Role.Button, onClick = { if (enabled) onClick() }, onLongClick = onLongClick)
            .then(if (enabled) Modifier else Modifier.semantics { disabled() })
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = if (small) AppType.caption else AppType.bodyStrong,
            fontWeight = FontWeight.SemiBold,
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun FoldRow(
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    info: Info? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = AppTheme.colors
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.heightIn(min = 44.dp).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LabelWithInfo(title, AppType.bodyStrong, c.text, info)
            if (!expanded) {
                Text(summary, style = AppType.caption, color = c.textMuted, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
            }
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = c.textFaint)
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(bottom = 8.dp), content = content)
        }
        Divider(color = c.divider, thickness = 1.dp)
    }
}

fun copyToClipboard(context: Context, clipboard: ClipboardManager, text: String) {
    clipboard.setText(AnnotatedString(text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

@Composable
fun CopyRow(label: String, value: String?, modifier: Modifier = Modifier, emptyText: String = "Not set", info: Info? = null) {
    val c = AppTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    SettingRow(label, modifier, onClick = value?.let { { copyToClipboard(context, clipboard, it) } }, info = info) {
        Text(value?.let { middleEllipsis(it) } ?: emptyText, style = AppType.mono, color = c.textMuted)
        if (value != null) Text("Copy", style = AppType.label, color = c.accent)
    }
}

fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))),
        cornerRadius = CornerRadius(radius.toPx()),
    )
}
