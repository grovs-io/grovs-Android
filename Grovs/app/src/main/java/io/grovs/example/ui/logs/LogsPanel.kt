package io.grovs.example.ui.logs

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.grovs.BuildConfig
import io.grovs.example.AppGraph
import io.grovs.example.PanelState
import io.grovs.example.actions.LinkSource
import io.grovs.example.actions.LogEntry
import io.grovs.example.actions.LogFilter
import io.grovs.example.actions.LogState
import io.grovs.example.actions.LogType
import io.grovs.example.actions.exportLog
import io.grovs.example.data.deviceName
import io.grovs.example.data.formatClock
import io.grovs.example.ui.controls.AppButton
import io.grovs.example.ui.controls.ButtonStyle
import io.grovs.example.ui.controls.Chip
import io.grovs.example.ui.controls.Sheet
import io.grovs.example.ui.controls.SettingRow
import io.grovs.example.ui.controls.StatusDot
import io.grovs.example.ui.controls.StatusPill
import io.grovs.example.ui.controls.TextAction
import io.grovs.example.ui.controls.copyToClipboard
import io.grovs.example.ui.shareText
import io.grovs.example.ui.theme.AppTheme
import io.grovs.example.ui.theme.AppType

@Composable
fun LogsPanel(panel: PanelState, entries: List<LogEntry>, onClose: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    var selectedId by remember(panel) { mutableStateOf(panel.entryId) }
    val selected = entries.firstOrNull { it.id == selectedId }
    Sheet(
        visible = panel.open,
        onDismiss = { if (selected != null && panel.entryId == null) selectedId = null else onClose() },
        heightFraction = 0.82f,
    ) {
        if (selected != null) {
            LogDetail(selected, onBack = { selectedId = null })
        } else {
            LogList(entries, filter, { filter = it }, onSelect = { selectedId = it.id })
        }
    }
}

@Composable
private fun ColumnScope.LogList(
    entries: List<LogEntry>,
    filter: LogFilter,
    onFilter: (LogFilter) -> Unit,
    onSelect: (LogEntry) -> Unit,
) {
    val c = AppTheme.colors
    val context = LocalContext.current
    val errors = entries.count { it.state == LogState.ERROR }
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Logs", style = AppType.heading, color = c.text, modifier = Modifier.weight(1f))
        TextAction("Share", {
            val profile = AppGraph.startedProfile
            val header = "Grovs test app log\nDevice: ${deviceName()} · Android ${Build.VERSION.RELEASE}\n" +
                "SDK: ${BuildConfig.SDK_VERSION} · Project: ${profile.name}"
            shareText(context, exportLog(entries, header) { formatClock(it, seconds = true) })
        })
        TextAction("Clear", AppGraph.log::clear, color = c.textMuted)
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LogFilter.values().forEach { f ->
            val isErrors = f == LogFilter.ERRORS
            Chip(
                if (isErrors && errors > 0) "${f.label} $errors" else f.label,
                { onFilter(f) },
                selected = f == filter,
                color = if (isErrors && errors > 0 && f != filter) c.error else null,
            )
        }
    }
    val shown = entries.filter(filter::matches)
    if (shown.isEmpty()) {
        Text("Nothing logged yet", style = AppType.caption, color = c.textMuted, modifier = Modifier.padding(vertical = 24.dp))
    }
    LazyColumn(Modifier.weight(1f)) {
        items(shown, key = { it.id }) { entry ->
            LogRow(entry, onClick = { onSelect(entry) })
            Divider(color = c.divider, thickness = 1.dp)
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry, onClick: () -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(entry.state)
        Text(entry.type.label, style = AppType.monoSmall, color = c.textMuted, modifier = Modifier.width(56.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = AppType.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (entry.detail.isNotBlank()) {
                Text(entry.detail, style = AppType.caption, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val action = entry.action
        if (action != null) {
            IconButton(onClick = { AppGraph.rerun(action) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "Run again", tint = c.accent)
            }
        }
    }
}

@Composable
private fun ColumnScope.LogDetail(entry: LogEntry, onBack: () -> Unit) {
    val c = AppTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.ArrowBack, "Back", tint = c.textMuted) }
        StatusDot(entry.state)
        Text(entry.title, style = AppType.heading, color = c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(formatClock(entry.time, seconds = true), style = AppType.caption, color = c.textMuted)
    }
    Box(
        Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface)
            .verticalScroll(rememberScrollState()).padding(12.dp),
    ) {
        Text(entry.code.ifBlank { entry.detail }, style = AppType.mono, color = c.text)
    }
    if (entry.type == LogType.LINK) {
        LinkSource.values().forEach { source ->
            SettingRow(source.name.lowercase().replaceFirstChar { it.uppercase() }) {
                if (source in entry.sources) StatusPill("received", LogState.OK) else StatusPill("missed", LogState.ERROR)
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppButton("Copy", { copyToClipboard(context, clipboard, entry.code.ifBlank { entry.detail }) }, Modifier.weight(1f), style = ButtonStyle.OUTLINE)
        entry.action?.let { action ->
            AppButton("Run again", { AppGraph.rerun(action) }, Modifier.weight(1f), style = ButtonStyle.OUTLINE)
        }
    }
}
