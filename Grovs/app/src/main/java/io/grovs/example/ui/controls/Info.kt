package io.grovs.example.ui.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.grovs.example.ui.theme.AppTheme
import io.grovs.example.ui.theme.AppType

/** A short explanation shown behind a "?" next to a label. */
data class Info(val title: String, val body: String)

@Composable
fun InfoButton(info: Info, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    var open by rememberSaveable { mutableStateOf(false) }
    Box(
        modifier.size(28.dp).clip(CircleShape)
            .clickable(onClickLabel = "About ${info.title}", role = Role.Button) { open = true },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(16.dp).border(1.dp, c.textFaint, CircleShape), contentAlignment = Alignment.Center) {
            Text("?", color = c.textMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    if (open) InfoDialog(info) { open = false }
}

@Composable
private fun InfoDialog(info: Info, onDismiss: () -> Unit) {
    val c = AppTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.background).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(info.title, style = AppType.heading, color = c.text)
            Text(info.body, style = AppType.body, color = c.textMuted)
            TextAction("Got it", onDismiss, Modifier.align(Alignment.End))
        }
    }
}
