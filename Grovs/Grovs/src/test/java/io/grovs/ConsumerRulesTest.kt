package io.grovs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConsumerRulesTest {

    // Unit tests run with the module directory as the working directory.
    private val rules = File("consumer-rules.pro").readText()

    private fun assertRule(rule: String) =
        assertTrue("consumer-rules.pro is missing: $rule", rules.contains(rule))

    @Test
    fun `the in-app listener and its java adapter keep their names and methods`() {
        assertRule("-keep interface io.grovs.GrovsInAppListener {")
        assertRule("-keep class io.grovs.GrovsInAppListenerAdapter {")
    }

    @Test
    fun `the html bridge channel keeps its javascript interface method`() {
        assertRule("-keepclassmembers class io.grovs.inapp.ui.InAppJavascriptChannel {")
        assertRule("@android.webkit.JavascriptInterface <methods>;")
    }

    @Test
    fun `no attribute rule pretends to keep javascript interface`() {
        assertFalse(rules.contains("-keepattributes JavascriptInterface"))
    }

    @Test
    fun `apps without the play review library still build`() {
        assertRule("-dontwarn com.google.android.play.core.review.**")
        assertRule("-dontwarn com.google.android.play.core.ktx.**")
    }
}
