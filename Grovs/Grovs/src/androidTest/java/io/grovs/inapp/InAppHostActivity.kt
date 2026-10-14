package io.grovs.inapp

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity

/** A plain app screen for in-app messages to sit on. No configChanges, so a rotation recreates it. */
class InAppHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Host app" })
    }
}
