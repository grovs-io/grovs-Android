package io.grovs.example.ui.inapp

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.grovs.example.SecondActivity
import io.grovs.example.ui.Help
import io.grovs.example.ui.controls.AppButton
import io.grovs.example.ui.controls.AppTextField
import io.grovs.example.ui.controls.ButtonStyle
import io.grovs.example.ui.controls.ChipInput
import io.grovs.example.ui.controls.ChipRow
import io.grovs.example.ui.controls.HelpText
import io.grovs.example.ui.controls.ScreenTitle
import io.grovs.example.ui.controls.SectionHeader
import io.grovs.example.ui.controls.Segment
import io.grovs.example.ui.controls.SettingRow
import io.grovs.example.ui.controls.SwitchRow
import io.grovs.example.ui.theme.AppTheme

@Composable
fun InAppScreen(vm: InAppViewModel = viewModel()) {
    val c = AppTheme.colors
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        ScreenTitle("In-app")
        Segment(listOf(false, true), vm.running, vm::setRunning, Modifier.fillMaxWidth()) { if (it) "Running" else "Paused" }

        SectionHeader("Show a message", info = Help.showMessage)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppTextField(vm.trigger, { vm.trigger = it }, Modifier.weight(1f), mono = true, onImeAction = vm::fireTrigger)
            AppButton("Trigger", vm::fireTrigger, small = true, enabled = vm.trigger.isNotBlank())
        }
        HelpText("Fires this event. Set a campaign with this trigger in the dashboard.")
        ChipRow(vm.triggerSuggestions, vm.trigger, { vm.trigger = it }, Modifier.padding(top = 6.dp))

        SectionHeader("Queue", info = Help.queue)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton("Discard waiting", { vm.discard(dismissCurrent = false) }, Modifier.weight(1f), style = ButtonStyle.OUTLINE, small = true)
            AppButton("Close current", { vm.discard(dismissCurrent = true) }, Modifier.weight(1f), style = ButtonStyle.OUTLINE, small = true)
        }

        SectionHeader("Rules")
        SwitchRow("Block all messages", vm.blockAll, vm::setBlockAll, info = Help.blockAll)
        SwitchRow("Debug logging", vm.debugLogging, vm::setDebugLogging, info = Help.inAppDebug)
        Spacer(Modifier.height(10.dp))
        ChipInput(vm.excluded, vm::setExcluded, label = "Excluded screens", info = Help.excludedScreens, placeholder = "Add screen", fieldTag = "excludedField")
        SettingRow("Open an excluded screen", onClick = { context.startActivity(Intent(context, SecondActivity::class.java)) }, showDivider = false) {
            Icon(Icons.Default.KeyboardArrowRight, null, tint = c.textFaint)
        }
    }
}
