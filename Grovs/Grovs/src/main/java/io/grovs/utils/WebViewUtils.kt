package io.grovs.utils

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebSettings
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class WebViewUtils {
    companion object {
        private val defaultUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"

        /// Set by reflection in both test suites (E2ETestUtils, SdkTestHelpers). Keep the name.
        @Volatile
        private var cachedUserAgent: String? = null

        /// When building the WebView last failed, or null. Each try runs on the main thread, so a
        /// failure is not retried for [RETRY_AFTER_FAILURE_MS]; the fallback is used meanwhile.
        @Volatile
        private var failedAt: Long? = null
        private const val RETRY_AFTER_FAILURE_MS = 300_000L

        internal val defaultElapsedMs: () -> Long = { SystemClock.elapsedRealtime() }
        internal var elapsedMs: () -> Long = defaultElapsedMs

        /// Builds the real user agent. Must run on the main thread: WebView requires it.
        internal val defaultBuildUserAgent: (Context) -> String = { context ->
            parseUserAgent(
                userAgent = WebView(context).settings.userAgentString,
                browserVersion = getChromeVersion(context = context),
            )
        }
        internal var buildUserAgent: (Context) -> String = defaultBuildUserAgent

        /**
         * Returns the user agent string of a WebView, or a fixed fallback when the WebView cannot
         * be built. At most one WebView build per [RETRY_AFTER_FAILURE_MS] after a failure.
         */
        fun getUserAgent(context: Context): String {
            cachedUserAgent?.let { return it }
            failedAt?.let { if (elapsedMs() - it < RETRY_AFTER_FAILURE_MS) return defaultUserAgent }
            return try {
                val userAgent = if (Looper.myLooper() === Looper.getMainLooper()) {
                    buildUserAgent(context)
                } else {
                    runBlocking(Dispatchers.Main) { buildUserAgent(context) }
                }
                cachedUserAgent = userAgent
                failedAt = null
                userAgent
            } catch (e: Exception) {
                failedAt = elapsedMs()
                defaultUserAgent
            }
        }

        internal fun resetForTests() {
            cachedUserAgent = null
            failedAt = null
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
                return defaultUserAgent
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