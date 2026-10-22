package io.grovs.example

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import io.grovs.example.actions.EventActions
import io.grovs.example.actions.LogState
import io.grovs.example.ui.controls.AppButton
import io.grovs.example.ui.controls.ButtonStyle
import io.grovs.example.ui.controls.HelpText
import io.grovs.example.ui.controls.SectionHeader
import io.grovs.example.ui.controls.SettingRow
import io.grovs.example.ui.controls.StatusDot
import io.grovs.example.ui.controls.StatusPill
import io.grovs.example.ui.theme.AppTheme
import io.grovs.example.ui.theme.AppType
import io.grovs.example.ui.theme.GrovsTestAppTheme
import kotlinx.coroutines.launch

/** A plain screen to test in-app message exclusion: the SDK sees it as "SecondActivity". */
class SecondActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GrovsTestAppTheme {
                ExcludedScreen(onBack = ::finish)
            }
        }
    }
}

@Composable
private fun ExcludedScreen(onBack: () -> Unit) {
    val c = AppTheme.colors
    val scope = rememberCoroutineScope()
    val store = AppGraph.store
    val screenName = SecondActivity::class.java.simpleName
    val excluded = store.excludedScreens.any { it.equals(screenName, ignoreCase = true) }
    val trigger = store.triggerEvent
    val entries by AppGraph.log.entries.collectAsState()
    val latest = entries.firstOrNull()

    Surface(color = c.background, contentColor = c.text) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.padding(end = 4.dp)) { Icon(Icons.Default.ArrowBack, "Back", tint = c.textMuted) }
                Text("Excluded screen", style = AppType.title, color = c.text)
            }
            Text(
                "This is $screenName. While it is in the excluded list, in-app messages wait instead of showing here. " +
                    "Trigger one below, then go back: it should show on the previous screen.",
                style = AppType.body, color = c.textMuted, modifier = Modifier.padding(vertical = 8.dp),
            )

            SectionHeader("Status")
            SettingRow("This screen") {
                if (excluded) StatusPill("excluded", LogState.OK) else StatusPill("not excluded", LogState.WARNING)
            }
            if (!excluded) HelpText("Add $screenName to Excluded screens on the In-app tab to test exclusion.")

            SectionHeader("Test")
            AppButton(
                "Trigger $trigger", { scope.launch { AppGraph.runner.run(EventActions.trigger(trigger)) } },
                Modifier.fillMaxWidth(), enabled = trigger.isNotBlank(),
            )
            if (latest != null) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusDot(latest.state)
                    Text("${latest.title} · ${latest.detail}", style = AppType.caption, color = c.textMuted, maxLines = 2)
                }
            }
            AppButton("Back", onBack, Modifier.fillMaxWidth().padding(top = 16.dp), style = ButtonStyle.OUTLINE)
        }
    }
}
