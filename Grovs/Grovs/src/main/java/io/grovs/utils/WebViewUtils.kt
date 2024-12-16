package io.grovs.utils

import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class WebViewUtils {
    companion object {
        /**
         * Returns the user agent string of a WebView.
         *
         * @param context The context to use for creating a WebView instance.
         * @return The user agent string of the WebView.
         */
        fun getUserAgent(context: Context): String {
            // Perform a thread check before using runBlocking
            if (Thread.currentThread().name.contains("main", ignoreCase = true)) {
                val webView = WebView(context)

                // Get WebSettings from the WebView
                val webSettings: WebSettings = webView.settings

                // Retrieve and return the user agent string
                val userAgent = webSettings.userAgentString
                val processedUserAgent = parseUserAgent(userAgent)

                return processedUserAgent
            } else {
                val result = runBlocking(Dispatchers.Main) {
                    // Create a WebView instance
                    val webView = WebView(context)

                    // Get WebSettings from the WebView
                    val webSettings: WebSettings = webView.settings

                    // Retrieve and return the user agent string
                    val userAgent = webSettings.userAgentString
                    val processedUserAgent = parseUserAgent(userAgent)

                    processedUserAgent
                }
                return result
            }
        }

        fun parseUserAgent(userAgent: String): String {
            try {
                val mozillaVersionRegex = """Mozilla/(\d+\.\d+)""".toRegex()
                val browserEngineRegex = """AppleWebKit/(\d+\.\d+)""".toRegex()
                val browserNameRegex = """Chrome/(\d+)""".toRegex()
                val mobileSafariVersionRegex = """Mobile Safari/(\d+\.\d+)""".toRegex()

                val mozillaVersion = mozillaVersionRegex.find(userAgent)?.groupValues?.get(1) ?: "5.0"
                val browserEngine = browserEngineRegex.find(userAgent)?.groupValues?.get(1) ?: "537.36"
                val browserName = browserNameRegex.find(userAgent)?.groupValues?.get(1) ?: "127"
                val mobileSafariVersion = mobileSafariVersionRegex.find(userAgent)?.groupValues?.get(1) ?: "537.36"

                return "Mozilla/$mozillaVersion (Linux; Android 10; K) AppleWebKit/$browserEngine (KHTML, like Gecko) Chrome/$browserName.0.0.0 Mobile Safari/$mobileSafariVersion"
            } catch (e: Exception) {
                return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
            }
        }

    }
}