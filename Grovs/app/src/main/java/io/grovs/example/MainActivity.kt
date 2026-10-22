package io.grovs.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import com.google.firebase.messaging.FirebaseMessaging
import io.grovs.Grovs
import io.grovs.example.actions.LinkSource
import io.grovs.example.actions.LogState
import io.grovs.example.actions.LogType
import io.grovs.example.actions.callCode
import io.grovs.example.data.middleEllipsis
import io.grovs.example.ui.AppScaffold
import io.grovs.example.ui.home.HomeViewModel
import io.grovs.example.ui.theme.GrovsTestAppTheme

class MainActivity : FragmentActivity() {
    private val home: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GrovsTestAppTheme { AppScaffold() } }

        logPushOpen(intent)
        Grovs.setOnDeeplinkReceivedListener(this) { details ->
            AppGraph.log.recordLinkOpen(details.link, details.data, details.tracking, LinkSource.LISTENER)
        }
        fetchPushToken()
    }

    override fun onResume() {
        super.onResume()
        home.refreshPermission()
    }

    override fun onStart() {
        super.onStart()
        Grovs.onStart(launcherActivity = this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        logPushOpen(intent)
        Grovs.onNewIntent(intent, launcherActivity = this)
    }

    fun fetchPushToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                AppGraph.log.add(LogType.PUSH, LogState.WARNING, "Push token failed", task.exception?.message.orEmpty())
                return@addOnCompleteListener
            }
            val token = task.result
            Grovs.pushToken = token
            AppGraph.pushToken.value = token
            AppGraph.log.add(LogType.PUSH, LogState.OK, "Push token registered", middleEllipsis(token),
                callCode("pushToken", "value" to middleEllipsis(token)))
        }
    }

    private fun logPushOpen(intent: Intent?) {
        if (!Grovs.isGrovsNotification(intent)) return
        val extras = intent?.extras ?: return
        @Suppress("DEPRECATION")
        val values = extras.keySet().associateWith { extras.get(it)?.toString() }
        AppGraph.log.add(LogType.PUSH, LogState.OK, "Push opened", values.keys.joinToString(), callCode("pushOpen", "extras" to values))
    }
}
