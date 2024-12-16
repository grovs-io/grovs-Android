package io.grovs.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.tasks.OnCompleteListener
import com.google.firebase.messaging.FirebaseMessaging
import io.grovs.Grovs
import io.grovs.example.ui.theme.GrovsTestAppTheme
import io.grovs.utils.flow
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val requestNotificationsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // FCM SDK (and your app) can post notifications.
            updateFCMToken()
        } else {
            // TODO: Inform user that that your app will not show notifications.
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GrovsTestAppTheme {
                CenteredTextViewAndButton(viewModel)
            }
        }

        askNotificationPermission()

        Grovs.setOnDeeplinkReceivedListener(this) { link, payload ->
            val message = "Got link from listener: $link payload: $payload"
            Log.d("MainActivity", message)

            viewModel.updateCallbackState(message)
        }

        lifecycleScope.launchWhenStarted {
            Grovs.Companion::openedLinkDetails.flow.collect { deeplinkDetails ->
                val message = "Got link from flow: ${deeplinkDetails?.link} payload: ${deeplinkDetails?.data}"
                Log.d("MainActivity", message)

                viewModel.updateFlowState(message)
            }
        }

        // Notifications

        Grovs.setOnAutomaticNotificationsListener { isLast ->
            Log.d("MainActivity", "Dismissed automatic notification is last: $isLast")

            Toast.makeText(this, "Dismissed automatic notification is last: $isLast", Toast.LENGTH_LONG).show()
        }

    }

    override fun onStart() {
        super.onStart()

        Grovs.onStart()

        lifecycleScope.launch {
            val result = Grovs.numberOfUnreadMessages()
            viewModel.updateUnreadNotificationsState(result ?: 0)
            Log.d("MainActivity", "Number of unread notifications: $result")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        Grovs.onNewIntent(intent)
    }

    private fun askNotificationPermission() {
        // This is only necessary for API level >= 33 (TIRAMISU)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) { } else if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                requestNotificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                requestNotificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        updateFCMToken()
    }

    private fun updateFCMToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.d("MainActivity", "Fetching FCM registration token failed", task.exception)

                Toast.makeText(this, "Fetching FCM registration token failed", Toast.LENGTH_LONG)
                    .show()

                return@addOnCompleteListener
            }

            // Get new FCM registration token
            val token = task.result
            Log.d("MainActivity", "FCM token: $token")
            Toast.makeText(this, "FCM token: $token", Toast.LENGTH_LONG).show()

            Grovs.pushToken = token
        }
    }
}

@Composable
fun CenteredTextViewAndButton(viewModel: MainViewModel) {
    // State for the text content
    val callbackGeneratedLinkState = remember { mutableStateOf("Callback generated link") }
    val flowGeneratedLinkState = remember { mutableStateOf("Flow generated link") }
    val incomingLinkCallbackState by viewModel::incomingLinkCallbackState
    val incomingLinkFlowState by viewModel::incomingLinkFlowState
    val unreadNotificationsState by viewModel::unreadNotificationsState
    val context = LocalContext.current
    val activity = context as? MainActivity
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CopyableText(
                text = callbackGeneratedLinkState.value,
            )

            CopyableText(
                text = flowGeneratedLinkState.value,
            )

            Button(onClick = {
                Grovs.generateLink(title = "Test title",
                    subtitle = "Test subtitle",
                    imageURL = null,
                    data = mapOf("param1" to "Test value"),
                    tags = null,
                    lifecycleOwner = activity,
                    listener = { link, error ->
                        link?.let { link ->
                            callbackGeneratedLinkState.value = link
                        }
                        error?.let { error ->
                            callbackGeneratedLinkState.value = error.toString()
                        }
                    })

            }) {
                Text(text = "Generate link with callback")
            }

            Button(onClick = {
                coroutineScope.launch {
                    val link = Grovs.generateLink(title = "Test title",
                        subtitle = "Test subtitle",
                        imageURL = null,
                        data = mapOf("param1" to "Test value"),
                        tags = null)
                    flowGeneratedLinkState.value = link
                }
            }) {
                Text(text = "Generate link with flow")
            }

            Text(
                text = incomingLinkCallbackState,
                modifier = Modifier.padding(top = 16.dp)
            )

            Text(
                text = incomingLinkFlowState,
                modifier = Modifier.padding(top = 16.dp)
            )

            Button(
                modifier = Modifier.padding(top = 16.dp),
                onClick = {
                Grovs.displayMessagesFragment {
                    Log.d("MainActivity", "Notifications screen dismissed")
                    Toast.makeText(context, "Notifications screen dismissed", Toast.LENGTH_LONG).show()
                }
            }) {
                Text(text = "Show notifications")
            }

            Text(
                text = unreadNotificationsState.toString(),
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    GrovsTestAppTheme {
        CenteredTextViewAndButton(viewModel = MainViewModel())
    }
}

@Composable
fun CopyableText(text: String) {
    val clipboardManager: ClipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Text(
        text = text,
        modifier = Modifier
            .padding(16.dp)
            .clickable {
            // Copy the text to clipboard
            clipboardManager.setText(AnnotatedString(text))

            // Show a confirmation toast
            Toast.makeText(context, "Text copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    )
}