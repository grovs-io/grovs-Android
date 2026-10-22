package io.grovs.example.ui.events

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.grovs.example.data.KeyValue
import io.grovs.example.ui.Help
import io.grovs.example.ui.controls.AppButton
import io.grovs.example.ui.controls.AppTextField
import io.grovs.example.ui.controls.ButtonStyle
import io.grovs.example.ui.controls.ChipInput
import io.grovs.example.ui.controls.ChipRow
import io.grovs.example.ui.controls.FoldRow
import io.grovs.example.ui.controls.KeyValueRows
import io.grovs.example.ui.controls.LocalSheetHost
import io.grovs.example.ui.controls.ScreenTitle
import io.grovs.example.ui.controls.SectionHeader
import io.grovs.example.ui.theme.AppTheme
import io.grovs.example.ui.theme.AppType

@Composable
fun EventsScreen(vm: EventsViewModel = viewModel()) {
    val c = AppTheme.colors
    val sheets = LocalSheetHost.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        ScreenTitle("Events")

        SectionHeader("Custom event", info = Help.customEvent)
        EventFormFields(vm.event, vm::updateEvent, vm.eventSuggestions, vm.eventError, vm::trackEvent, onSendAnyway = { vm.trackEvent(force = true) })
        AppButton(vm.eventError ?: "Track", { vm.trackEvent() }, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = vm.eventError == null)

        SectionHeader("Screen view", info = Help.screenView)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppTextField(vm.screen.name, { v -> vm.updateScreen { it.copy(name = v) } }, Modifier.weight(1f), onImeAction = vm::trackScreen)
            AppButton(
                "Track", vm::trackScreen, small = true, enabled = vm.screen.name.isNotBlank(),
                onLongClick = {
                    sheets.show {
                        Text("Screen properties", style = AppType.heading, color = c.text, modifier = Modifier.padding(bottom = 8.dp))
                        KeyValueRows(vm.screen.properties, { v -> vm.updateScreen { it.copy(properties = v) } })
                        AppButton("Save and track", { vm.trackScreen(); sheets.hide() }, Modifier.fillMaxWidth().padding(top = 12.dp))
                    }
                },
            )
        }
        ChipRow(vm.screenSuggestions, vm.screen.name, { v -> vm.updateScreen { it.copy(name = v) } }, Modifier.padding(top = 6.dp))

        SectionHeader("Setup")
        FoldRow("Global tags", vm.globalTags.joinToString().ifEmpty { "none" }, info = Help.globalTags) {
            ChipInput(vm.globalTags, vm::applyGlobalTags, placeholder = "Add tag", fieldTag = "globalTagsField")
        }
        FoldRow("Screen aliases", vm.aliases.joinToString { "${it.key} → ${it.raw}" }.ifEmpty { "none" }, info = Help.screenAliases) {
            var draft by remember(vm.aliases) { mutableStateOf<List<KeyValue>>(vm.aliases) }
            KeyValueRows(draft, { draft = it }, textOnly = true)
            AppButton("Apply aliases", { vm.applyAliases(draft) }, Modifier.fillMaxWidth().padding(top = 8.dp), style = ButtonStyle.OUTLINE)
        }

        SectionHeader("Purchase", info = Help.purchase)
        PurchaseSection(vm)
    }
}

@Composable
private fun PurchaseSection(vm: EventsViewModel) {
    val c = AppTheme.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    val valid = vm.purchase.isValid()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AppButton(
            vm.purchase.fullLabel(), vm::logPurchase, Modifier.weight(1f),
            style = if (expanded) ButtonStyle.PRIMARY else ButtonStyle.OUTLINE, enabled = valid,
        )
        IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(40.dp)) {
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, "Edit purchase", tint = c.textMuted)
        }
    }
    AnimatedVisibility(expanded) {
        Column(Modifier.padding(top = 10.dp)) {
            PurchaseFormFields(vm.purchase, vm::updatePurchase)
            FoldRow("Google Play purchase JSON", if (vm.purchase.playJson.isBlank()) "empty" else "pasted", info = Help.playPurchase) {
                AppTextField(
                    vm.purchase.playJson, { v -> vm.updatePurchase { it.copy(playJson = v) } }, Modifier.heightIn(min = 120.dp),
                    placeholder = "purchase.originalJson", mono = true, singleLine = false, imeAction = ImeAction.Default,
                )
                Spacer(Modifier.height(8.dp))
                AppButton("Log Play purchase", vm::logPlayPurchase, Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE, enabled = vm.purchase.playJson.isNotBlank())
            }
        }
    }
}
