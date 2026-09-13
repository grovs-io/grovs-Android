package io.grovs.fragments

import android.content.DialogInterface
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import io.grovs.Grovs
import io.grovs.R
import io.grovs.databinding.FragmentAutoDisplayedNotificationBinding
import io.grovs.model.notifications.Notification
import io.grovs.service.GrovsService
import io.grovs.utils.applySystemBarInsets
import io.grovs.viewmodels.AutoDisplayedNotificationViewModel

const val ARG_NOTIFICATION = "notification"

/**
 * Keeps a no-argument constructor: Android rebuilds saved fragments through it whenever the
 * host Activity is recreated. The service and dismiss callback arrive through [bind] before the
 * first show and live in the ViewModel from then on.
 */
class AutoDisplayedNotificationFragment : DialogFragment() {
    private lateinit var binding: FragmentAutoDisplayedNotificationBinding
    private val viewModel: AutoDisplayedNotificationViewModel by viewModels()

    private var notification: Notification? = null

    private var pendingService: GrovsService? = null
    private var pendingOnDismissed: (() -> Unit)? = null

    companion object {

        @JvmStatic
        fun newInstance(notification: Notification, grovsService: GrovsService, onDismissed: (() -> Unit)? = null) =
            AutoDisplayedNotificationFragment().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_NOTIFICATION, notification)
                }
                bind(grovsService, onDismissed)
            }
    }

    @JvmSynthetic
    internal fun bind(grovsService: GrovsService, onDismissed: (() -> Unit)?) {
        pendingService = grovsService
        pendingOnDismissed = onDismissed
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelable(ARG_NOTIFICATION, Notification::class.java)
            } else {
                it.getParcelable(ARG_NOTIFICATION)
            }
        }

        setStyle(STYLE_NO_TITLE, R.style.GrovsFullScreenDialogStyle)
        pendingService?.let {
            viewModel.grovsService = it
            viewModel.onDismissed = pendingOnDismissed
        }
        pendingService = null
        pendingOnDismissed = null
        if (viewModel.grovsService == null) {
            // Restored after process death: only the configured SDK can supply the service again.
            Grovs.activeNotificationsManager?.let { manager ->
                viewModel.grovsService = manager.grovsService
                viewModel.onDismissed = { manager.automaticNotificationClosed() }
            }
        }
        if (viewModel.grovsService == null) dismissAllowingStateLoss()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        binding = FragmentAutoDisplayedNotificationBinding.inflate(inflater, container, false)

        return binding.root
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (viewModel.grovsService == null) return
        applySystemBarInsets()
        setup()
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)

        viewModel.onDismissed?.invoke()
    }

    private fun setup() {
        binding.closeButton.setOnClickListener {
            dismiss()
            viewModel.onDismissed?.invoke()
        }

        // Configure the WebView settings
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                if (notification?.read == false) {
                    notification?.accessURL?.let {
                        if (url == "https://$it") {
                            viewModel.markAsRead(notification!!)
                        }
                    }
                }
            }
        }
        val webSettings: WebSettings = binding.webView.settings
        webSettings.javaScriptEnabled = true  // Enable JavaScript if needed

        binding.webView.setBackgroundColor(Color.TRANSPARENT)

        // Load a URL in the WebView
        notification?.accessURL?.let {
            binding.webView.loadUrl("https://$it")
        }
    }

}
