package io.grovs.fragments

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.viewModels
import io.grovs.databinding.FragmentNotificationDetailsBinding
import io.grovs.model.notifications.Notification
import io.grovs.utils.applySystemBarInsets
import io.grovs.viewmodels.NotificationsMainViewModel


class NotificationDetailsFragment : Fragment() {
    private lateinit var binding: FragmentNotificationDetailsBinding
    private val viewModel: NotificationsMainViewModel by viewModels(ownerProducer = { requireParentFragment().requireParentFragment() })

    private var notification: Notification? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        arguments?.let {
            notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelable(ARG_NOTIFICATION, Notification::class.java)
            } else {
                it.getParcelable(ARG_NOTIFICATION)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        binding = FragmentNotificationDetailsBinding.inflate(inflater, container, false)

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setup()
    }

    private fun setup() {
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