package io.grovs.inapp.ui

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Carries the page's messages to the session, both ways named `grovsAndroid` so the boot script is the same. */
internal object InAppHtmlTransport {
    fun install(webView: WebView, receive: (raw: String, mainFrame: Boolean) -> Unit): () -> Unit {
        if (supportsMessageListener()) {
            // Runs on the main thread and knows the frame, which the token alone cannot tell.
            WebViewCompat.addWebMessageListener(webView, InAppHtmlDocument.CHANNEL, setOf("*")) { _, message, _, isMainFrame, _ ->
                message.data?.let { receive(it, isMainFrame) }
            }
            return { WebViewCompat.removeWebMessageListener(webView, InAppHtmlDocument.CHANNEL) }
        }
        // Every frame sees this object and it says nothing about the frame: the token is the only guard here.
        val main = Handler(Looper.getMainLooper())
        webView.addJavascriptInterface(InAppJavascriptChannel { raw -> main.post { receive(raw, true) } }, InAppHtmlDocument.CHANNEL)
        return { webView.removeJavascriptInterface(InAppHtmlDocument.CHANNEL) }
    }

    private fun supportsMessageListener(): Boolean = try {
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    } catch (e: Throwable) {
        false
    }
}

/** Called on a binder thread by the page; the transport moves each message to the main thread. */
internal class InAppJavascriptChannel(private val receive: (String) -> Unit) {
    @JavascriptInterface
    fun postMessage(message: String) {
        receive(message)
    }
}
