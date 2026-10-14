package io.grovs.example

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.grovs.Grovs

/** Use this event as a campaign trigger in the dashboard to try in-app messages. */
const val DEMO_EVENT = "demo_tapped"

@Composable
fun InAppControls() {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(top = 16.dp),
    ) {
        Text(text = "In-app messages")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(8.dp),
        ) {
            Button(onClick = { Grovs.pauseInAppMessages() }, modifier = Modifier.weight(1f)) {
                Text(text = "Pause")
            }
            Button(onClick = { Grovs.resumeInAppMessages() }, modifier = Modifier.weight(1f)) {
                Text(text = "Resume")
            }
            Button(onClick = { Grovs.discardInAppMessages(dismissCurrent = true) }, modifier = Modifier.weight(1f)) {
                Text(text = "Discard")
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(8.dp),
        ) {
            Button(onClick = { Grovs.track(DEMO_EVENT) }, modifier = Modifier.weight(1f)) {
                Text(text = "Track $DEMO_EVENT")
            }
            Button(
                onClick = { context.startActivity(Intent(context, SecondActivity::class.java)) },
                modifier = Modifier.weight(1f),
            ) {
                Text(text = "Open excluded screen")
            }
        }
    }
}
