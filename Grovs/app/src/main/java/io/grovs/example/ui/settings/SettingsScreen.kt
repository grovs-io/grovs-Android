package io.grovs.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.grovs.example.data.CallStyle
import io.grovs.example.data.apiKeyError
import io.grovs.example.data.baseUrlError
import io.grovs.example.data.middleEllipsis
import io.grovs.example.data.profileNameError
import io.grovs.example.ui.Help
import io.grovs.example.ui.controls.AppButton
import io.grovs.example.ui.controls.AppTextField
import io.grovs.example.ui.controls.Chip
import io.grovs.example.ui.controls.ChipInput
import io.grovs.example.ui.controls.FieldLabel
import io.grovs.example.ui.controls.FoldRow
import io.grovs.example.ui.controls.Info
import io.grovs.example.ui.controls.HelpText
import io.grovs.example.ui.controls.SectionHeader
import io.grovs.example.ui.controls.Segment
import io.grovs.example.ui.controls.SettingRow
import io.grovs.example.ui.controls.SwitchRow
import io.grovs.example.ui.controls.TextAction
import io.grovs.example.ui.restartApp
import io.grovs.example.ui.theme.AppTheme
import io.grovs.example.ui.theme.AppType
import io.grovs.model.LogLevel

@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val c = AppTheme.colors
    val context = LocalContext.current
    val profile = vm.active
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = c.textMuted) }
                Text("Settings", style = AppType.title, color = c.text)
            }

            SectionHeader("Project")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.profiles.forEach { p -> Chip(p.name, { vm.select(p.id) }, selected = p.id == profile.id) }
                Chip("+ New", vm::addProfile, dashed = true)
            }
            EditableRow("Name", profile.name, { v -> vm.updateActive { it.copy(name = v.trim()) } }, ::profileNameError, mono = false)
            EditableRow("API key", profile.apiKey, { v -> vm.updateActive { it.copy(apiKey = v.trim()) } }, ::apiKeyError, info = Help.apiKey)
            SettingRow("Environment", info = Help.environment) {
                Segment(listOf(false, true), profile.useTestEnvironment, { v -> vm.updateActive { it.copy(useTestEnvironment = v) } }, Modifier.width(140.dp), compact = true) {
                    if (it) "Test" else "Prod"
                }
            }
            EditableRow("Base URL", profile.baseUrl.orEmpty(), { v -> vm.updateActive { it.copy(baseUrl = v.trim().ifEmpty { null }) } },
                ::baseUrlError, emptyText = "SDK default", keyboardType = KeyboardType.Uri, info = Help.baseUrl)
            FoldRow("Clipboard domains", profile.clipboardDomains.joinToString().ifEmpty { "none" }, info = Help.clipboardDomains) {
                ChipInput(profile.clipboardDomains, { v -> vm.updateActive { it.copy(clipboardDomains = v) } }, placeholder = "links.example.com", fieldTag = "domainsField")
            }
            SwitchRow("Auto track screens", profile.autoTrackScreens, { v -> vm.updateActive { it.copy(autoTrackScreens = v) } }, info = Help.autoTrackScreens)
            SwitchRow("Start with SDK enabled", profile.startEnabled, { v -> vm.updateActive { it.copy(startEnabled = v) } }, info = Help.startEnabled)
            if (vm.profiles.size > 1) TextAction("Delete this profile", vm::deleteActive, Modifier.padding(top = 6.dp), color = c.error)

            SectionHeader("Developer")
            SettingRow("Debug level", info = Help.debugLevel) {
                Segment(LogLevel.values().toList(), vm.debugLevel, vm::changeDebugLevel, Modifier.width(140.dp), compact = true) {
                    it.name.lowercase().replaceFirstChar(Char::uppercase)
                }
            }
            SettingRow("Call style", showDivider = false, info = Help.callStyle) {
                Segment(CallStyle.values().toList(), vm.callStyle, vm::changeCallStyle, Modifier.width(170.dp), compact = true) {
                    it.label.replaceFirstChar(Char::uppercase)
                }
            }
        }
        val changes = vm.restartChanges
        if (changes.isNotEmpty()) {
            Row(
                Modifier.navigationBarsPadding().padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0B1220)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Restart to apply", style = AppType.label, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text(changes.joinToString(", "), style = AppType.caption, color = Color(0xFF94A3B8))
                }
                AppButton("Restart", { restartApp(context) }, small = true)
            }
        }
    }
}

@Composable
private fun EditableRow(
    label: String,
    value: String,
    onSave: (String) -> Unit,
    error: (String) -> String?,
    emptyText: String = "Not set",
    keyboardType: KeyboardType = KeyboardType.Text,
    mono: Boolean = true,
    info: Info? = null,
) {
    val c = AppTheme.colors
    val clipboard = LocalClipboardManager.current
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable(value) { mutableStateOf(value) }
    if (!editing) {
        SettingRow(label, onClick = { draft = value; editing = true }, info = info) {
            Text(value.ifEmpty { emptyText }.let { if (value.length > 24) middleEllipsis(it, 8) else it }, style = if (mono) AppType.mono else AppType.body, color = c.textMuted)
        }
        return
    }
    val message = error(draft)
    val save = {
        if (message == null) {
            onSave(draft)
            editing = false
        }
    }
    Column(Modifier.padding(vertical = 6.dp)) {
        FieldLabel(label, info = info)
        AppTextField(
            draft, { draft = it }, mono = mono, error = message != null, keyboardType = keyboardType,
            onImeAction = save,
            trailing = { TextAction("Paste", { clipboard.getText()?.text?.let { draft = it.trim() } }) },
        )
        if (message != null) HelpText(message, isError = true)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
            TextAction("Cancel", { editing = false }, color = c.textMuted)
            TextAction("Save", save, enabled = message == null)
        }
    }
}
