package io.grovs.example.ui.home

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.grovs.BuildConfig
import io.grovs.example.AppGraph
import io.grovs.example.MainActivity
import io.grovs.example.actions.LinkSource
import io.grovs.example.actions.LogEntry
import io.grovs.example.actions.LogState
import io.grovs.example.actions.LogType
import io.grovs.example.actions.TestLog
import io.grovs.example.data.deviceName
import io.grovs.example.data.isWebUrl
import io.grovs.example.data.relativeTime
import io.grovs.example.data.summary
import io.grovs.example.ui.controls.AppButton
import io.grovs.example.ui.controls.ButtonStyle
import io.grovs.example.ui.controls.Chip
import io.grovs.example.ui.controls.CopyRow
import io.grovs.example.ui.controls.KeyValueRows
import io.grovs.example.ui.controls.LocalSheetHost
import io.grovs.example.ui.controls.SectionHeader
import io.grovs.example.ui.controls.SettingRow
import io.grovs.example.ui.controls.StatusPill
import io.grovs.example.ui.controls.SuggestField
import io.grovs.example.ui.controls.SwitchRow
import io.grovs.example.ui.controls.TextAction
import io.grovs.example.ui.events.EventFormFields
import io.grovs.example.ui.events.EventsViewModel
import io.grovs.example.ui.events.PurchaseFormFields
import io.grovs.example.ui.links.LinkFormFields
import io.grovs.example.ui.links.LinksViewModel
import io.grovs.example.ui.openLinkInApp
import io.grovs.example.ui.theme.AppTheme
import io.grovs.example.ui.theme.AppType

@Composable
fun HomeScreen(onOpenSettings: () -> Unit, vm: HomeViewModel = viewModel()) {
    val c = AppTheme.colors
    val profile = AppGraph.startedProfile
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(c.accent), contentAlignment = Alignment.Center) {
                Text("g", style = AppType.label, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Text("Grovs Test", style = AppType.title, color = c.text, modifier = Modifier.weight(1f))
            Text("${if (profile.useTestEnvironment) "Test" else "Prod"} · ${BuildConfig.SDK_VERSION}", style = AppType.caption, color = c.textMuted)
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Settings, "Settings", tint = c.textMuted) }
        }
        StatusSection(vm, onOpenSettings)
        OpenedFromSection()
        IdentitySection(vm)
        QuickActions()
    }
}

@Composable
private fun StatusSection(vm: HomeViewModel, onOpenSettings: () -> Unit) {
    val c = AppTheme.colors
    val context = LocalContext.current
    val sdkEnabled by AppGraph.sdkEnabled.collectAsState()
    val token by AppGraph.pushToken.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.refreshPermission()
        (context as? MainActivity)?.fetchPushToken()
    }
    SectionHeader("Status")
    SwitchRow("SDK enabled", sdkEnabled, vm::setSdkEnabled)
    SettingRow("Project", onClick = onOpenSettings) {
        Text(AppGraph.startedProfile.name, style = AppType.body, color = c.textMuted)
        Icon(Icons.Default.KeyboardArrowRight, null, tint = c.textFaint)
    }
    SettingRow("Notifications") {
        if (vm.notificationsAllowed) {
            StatusPill("Allowed", LogState.OK)
        } else {
            StatusPill("Not allowed", LogState.WARNING)
            TextAction("Allow", {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }
            })
        }
    }
    CopyRow("Push token", token, emptyText = "Waiting…")
}

@Composable
private fun OpenedFromSection() {
    val c = AppTheme.colors
    val entries by AppGraph.log.entries.collectAsState()
    val latest = entries.firstOrNull { it.type == LogType.LINK }
    SectionHeader("Opened from", action = latest?.let { relativeTime(System.currentTimeMillis() - it.time) }, onAction = {})
    if (latest == null) {
        Text("Not opened from a link yet", style = AppType.caption, color = c.textMuted, modifier = Modifier.padding(vertical = 8.dp))
    } else {
        SettingRow(latest.link ?: "no link", onClick = { AppGraph.openPanel(latest.id) }) {
            LinkSourcesPill(latest)
        }
    }
}

@Composable
private fun LinkSourcesPill(entry: LogEntry) {
    val both = entry.sources.size == LinkSource.values().size
    val settled = System.currentTimeMillis() - entry.time > TestLog.MERGE_WINDOW_MS
    when {
        both -> StatusPill("listener ✓ flow ✓", LogState.OK)
        settled -> StatusPill("${LinkSource.values().first { it !in entry.sources }.name.lowercase()} missed", LogState.ERROR)
        else -> StatusPill("waiting", LogState.NEUTRAL)
    }
}

@Composable
private fun IdentitySection(vm: HomeViewModel) {
    val c = AppTheme.colors
    SectionHeader("Identity", action = if (vm.editingIdentity) null else "Change", onAction = vm::startEditing)
    if (!vm.editingIdentity) {
        SettingRow("Identifier", onClick = vm::startEditing) {
            Text(vm.identity.identifier ?: "None", style = AppType.body, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SettingRow("Attributes", onClick = vm::startEditing) {
            Text(vm.identity.attributes.summary().ifEmpty { "none" }, style = AppType.caption, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        return
    }
    val draft = vm.draft
    SuggestField(draft.identifier.orEmpty(), { v -> vm.updateDraft { it.copy(identifier = v.ifEmpty { null }) } }, emptyList(), label = "Identifier")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(deviceName(), "qa_user_1").forEach { value ->
            Chip(if (value == deviceName()) "Device name" else value, { vm.updateDraft { it.copy(identifier = value) } }, selected = draft.identifier == value)
        }
        Chip("Random id", { vm.updateDraft { it.copy(identifier = "qa_" + (100000..999999).random()) } })
        Chip("None", { vm.updateDraft { it.copy(identifier = null) } }, selected = draft.identifier == null, dashed = draft.identifier != null)
    }
    Spacer(Modifier.height(10.dp))
    KeyValueRows(draft.attributes, { v -> vm.updateDraft { it.copy(attributes = v) } }, label = "Attributes", suggestedKeys = listOf("plan", "country"))
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppButton("Cancel", vm::cancelEditing, Modifier.weight(1f), style = ButtonStyle.OUTLINE)
        AppButton("Save", vm::saveIdentity, Modifier.weight(1f))
    }
}

@Composable
private fun QuickActions() {
    val c = AppTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val sheets = LocalSheetHost.current
    val links: LinksViewModel = viewModel()
    val events: EventsViewModel = viewModel()
    SectionHeader("Quick actions", action = "hold to edit", onAction = {})
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppButton("Generate link", links::generate, Modifier.weight(1f), onLongClick = {
            sheets.show {
                Text("Generate link", style = AppType.heading, color = c.text, modifier = Modifier.padding(bottom = 8.dp))
                LinkFormFields(links.form, links::update)
                AppButton("Save and generate", { links.generate(); sheets.hide() }, Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        })
        AppButton("Open clipboard", {
            val text = clipboard.getText()?.text?.trim()
            when {
                text == null || !isWebUrl(text) -> Toast.makeText(context, "Clipboard has no link", Toast.LENGTH_SHORT).show()
                !openLinkInApp(context, text) -> Toast.makeText(context, "No screen in this app opens this link", Toast.LENGTH_SHORT).show()
            }
        }, Modifier.weight(1f), style = ButtonStyle.OUTLINE)
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppButton("Track ${events.event.name}", { events.trackEvent() }, Modifier.weight(1f), style = ButtonStyle.OUTLINE,
            enabled = events.eventError == null, onLongClick = {
                sheets.show {
                    Text("Track event", style = AppType.heading, color = c.text, modifier = Modifier.padding(bottom = 8.dp))
                    EventFormFields(events.event, events::updateEvent, events.eventSuggestions, events.eventError, { events.trackEvent() })
                    AppButton("Save and track", { events.trackEvent(); sheets.hide() }, Modifier.fillMaxWidth().padding(top = 12.dp), enabled = events.eventError == null)
                }
            })
        AppButton(events.purchase.label(), events::logPurchase, Modifier.weight(1f), style = ButtonStyle.OUTLINE, onLongClick = {
            sheets.show {
                Text("Purchase", style = AppType.heading, color = c.text, modifier = Modifier.padding(bottom = 8.dp))
                PurchaseFormFields(events.purchase, events::updatePurchase)
                AppButton("Save and log", { events.logPurchase(); sheets.hide() }, Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        })
    }
}
