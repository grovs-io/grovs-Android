package io.grovs.example.ui.links

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.grovs.example.actions.LogState
import io.grovs.example.data.adbCommand
import io.grovs.example.ui.Help
import io.grovs.example.ui.controls.AppButton
import io.grovs.example.ui.controls.LinkField
import io.grovs.example.ui.controls.LocalSheetHost
import io.grovs.example.ui.controls.MIN_TOUCH
import io.grovs.example.ui.controls.ScreenTitle
import io.grovs.example.ui.controls.SectionHeader
import io.grovs.example.ui.controls.StatusDot
import io.grovs.example.ui.controls.TextAction
import io.grovs.example.ui.controls.color
import io.grovs.example.ui.controls.copyToClipboard
import io.grovs.example.ui.controls.softColor
import io.grovs.example.ui.openLinkInApp
import io.grovs.example.ui.shareText
import io.grovs.example.ui.theme.AppTheme
import io.grovs.example.ui.theme.AppType

@Composable
fun LinksScreen(vm: LinksViewModel = viewModel()) {
    val c = AppTheme.colors
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 88.dp)) {
            ScreenTitle("Links", action = "Reset", onAction = vm::reset)
            LinkField(vm.inspectInput, { vm.inspectInput = it }, label = "Inspect a link", info = Help.inspect, placeholder = "Paste a link to inspect", showPath = true, onDone = { vm.inspect() })
            vm.result?.let { result ->
                LinkResultCard(result, onRunAgain = vm::runAgain, onInspect = { link -> vm.inspectInput = link; vm.inspect(link) })
            }
            SectionHeader("New link")
            LinkFormFields(vm.form, vm::update)
        }
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, c.background)))
                .padding(16.dp),
        ) {
            AppButton(if (vm.running) "Generating…" else "Generate link", vm::generate, Modifier.fillMaxWidth(), enabled = !vm.running)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkResultCard(result: LinkResult, onRunAgain: () -> Unit, onInspect: (String) -> Unit) {
    val c = AppTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val sheets = LocalSheetHost.current
    val state = if (result.ok) LogState.OK else LogState.ERROR
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(shape).background(state.softColor().copy(alpha = 0.35f))
            .border(1.dp, state.color().copy(alpha = 0.35f), shape).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusDot(state)
            Text(if (result.ok) "Created · ${result.message}" else "Failed", style = AppType.caption, color = c.textMuted, modifier = Modifier.weight(1f))
            IconButton(onClick = onRunAgain, modifier = Modifier.size(MIN_TOUCH)) {
                Icon(Icons.Default.Refresh, contentDescription = "Run again", tint = c.accent)
            }
        }
        val link = result.link
        if (result.ok && link != null) {
            Text(link, style = AppType.mono, color = c.accent, modifier = Modifier.padding(vertical = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextAction("Copy", { copyToClipboard(context, clipboard, link) })
                TextAction("Share", { shareText(context, link) })
                TextAction("QR", { sheets.show { QrSheet(link) } })
                TextAction("Open", {
                    if (!openLinkInApp(context, link)) Toast.makeText(context, "No screen in this app opens this link", Toast.LENGTH_SHORT).show()
                })
                TextAction("adb", { copyToClipboard(context, clipboard, adbCommand(link, context.packageName)) })
                TextAction("Inspect", { onInspect(link) })
            }
        } else {
            Text(result.message, style = AppType.caption, color = c.error, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
