package io.grovs.inapp.ui

import android.os.Looper
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class InAppHtmlTransportTest {
    @Test
    fun withoutAMessageListenerThePageSpeaksThroughTheInterfaceOnTheMainThread() {
        val web = WebView(ApplicationProvider.getApplicationContext())
        val received = mutableListOf<Pair<String, Boolean>>()
        val remove = InAppHtmlTransport.install(web) { raw, mainFrame -> received += raw to mainFrame }
        val channel = shadowOf(web).getJavascriptInterface(InAppHtmlDocument.CHANNEL) as InAppJavascriptChannel

        val binder = Thread { channel.postMessage("{\"v\":1}") }
        binder.start()
        binder.join()
        assertTrue("not delivered off the main thread", received.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("{\"v\":1}" to true), received)

        remove()
        assertNull(shadowOf(web).getJavascriptInterface(InAppHtmlDocument.CHANNEL))
    }

    @Test
    fun r8KeepsThePageCallableMethod() {
        val rules = File("consumer-rules.pro").readText()
        assertTrue(rules.contains("io.grovs.inapp.ui.InAppJavascriptChannel"))
        assertTrue(rules.contains("@android.webkit.JavascriptInterface <methods>;"))
    }
}
