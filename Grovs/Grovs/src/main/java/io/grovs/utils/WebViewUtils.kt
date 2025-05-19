package io.grovs.utils

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
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
                val processedUserAgent = parseUserAgent(userAgent = userAgent, browserVersion = getChromeVersion(context = context))

                return processedUserAgent
            } else {
                val result = runBlocking(Dispatchers.Main) {
                    // Create a WebView instance
                    val webView = WebView(context)

                    // Get WebSettings from the WebView
                    val webSettings: WebSettings = webView.settings

                    // Retrieve and return the user agent string
                    val userAgent = webSettings.userAgentString
                    val processedUserAgent = parseUserAgent(userAgent = userAgent, browserVersion = getChromeVersion(context = context))

                    processedUserAgent
                }
                return result
            }
        }

        fun parseUserAgent(userAgent: String, browserVersion: String?): String {
            try {
                val mozillaVersionRegex = """Mozilla/(\d+\.\d+)""".toRegex()
                val browserEngineRegex = """AppleWebKit/(\d+\.\d+)""".toRegex()
                val browserNameRegex = """Chrome/(\d+)""".toRegex()
                val mobileSafariVersionRegex = """Mobile Safari/(\d+\.\d+)""".toRegex()

                val mozillaVersion = mozillaVersionRegex.find(userAgent)?.groupValues?.get(1) ?: "5.0"
                val browserEngine = browserEngineRegex.find(userAgent)?.groupValues?.get(1) ?: "537.36"
                val browserName = browserVersion ?: browserNameRegex.find(userAgent)?.groupValues?.get(1) ?: "127"
                val mobileSafariVersion = mobileSafariVersionRegex.find(userAgent)?.groupValues?.get(1) ?: "537.36"

                return "Mozilla/$mozillaVersion (Linux; Android 10; K) AppleWebKit/$browserEngine (KHTML, like Gecko) Chrome/$browserName.0.0.0 Mobile Safari/$mobileSafariVersion"
            } catch (e: Exception) {
                return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
            }
        }

        fun getChromeVersion(context: Context): String? {
            val chromePackage = "com.android.chrome"
            val packageManager = context.packageManager

            return try {
                val packageInfo: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // API 33+: use new method
                    packageManager.getPackageInfo(
                        chromePackage,
                        PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    // API 21–32: use legacy method
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(chromePackage, 0)
                }

                val fullVersion = packageInfo.versionName
                // Extract the major version before the first dot
                fullVersion?.substringBefore('.')
            } catch (e: PackageManager.NameNotFoundException) {
                null
            } catch (e: Exception) {
                null
            }
        }
    }
}