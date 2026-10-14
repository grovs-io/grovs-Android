package io.grovs.inapp.ui

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.grovs.inapp.InAppBridge
import io.grovs.inapp.model.InAppLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class InAppHtmlDocumentTest {
    private val viewport = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"

    @Test
    fun addsAViewportOnlyWhenThePageHasNoneAndPutsTheScriptAfterTheDoctype() {
        assertEquals("<!doctype html>$viewport<s><p>Hi</p>", InAppHtmlDocument.document("<p>Hi</p>", "<s>"))
        assertEquals("<!DOCTYPE html>$viewport<s><p>Hi</p>", InAppHtmlDocument.document("<!DOCTYPE html><p>Hi</p>", "<s>"))
        val own = "<!doctype html><META Name='Viewport' content='width=300'><p>Hi</p>"
        assertEquals("<!doctype html><s><META Name='Viewport' content='width=300'><p>Hi</p>", InAppHtmlDocument.document(own, "<s>"))
        val commented = "<!-- banner -->\n<!DOCTYPE html><p>Hi</p>"
        assertEquals("<!-- banner -->\n<!DOCTYPE html>$viewport<s><p>Hi</p>", InAppHtmlDocument.document(commented, "<s>"))
    }

    @Test
    fun theScriptGoesBeforeAnyAuthorScriptWhateverCommentsFollow() {
        val tricky = "<!-- a --><p>x</p><script>evil()</script><!-- b --><!doctype html><p>y</p>"
        assertEquals("<!doctype html>$viewport<s>$tricky", InAppHtmlDocument.document(tricky, "<s>"))
        assertEquals("\uFEFF<!doctype html>$viewport<s><p>Hi</p>", InAppHtmlDocument.document("\uFEFF<!doctype html><p>Hi</p>", "<s>"))
        assertEquals("  \n<!DOCTYPE html>$viewport<s><p>Hi</p>", InAppHtmlDocument.document("  \n<!DOCTYPE html><p>Hi</p>", "<s>"))
    }

    @Test
    fun commentsEndTheWayBrowsersEndThem() {
        for (open in listOf("<!-->", "<!--->", "<!-- a --!>")) {
            val page = "$open<script>e()</script><!-- --><!doctype html><p>y</p>"
            assertEquals("<!doctype html>$viewport<s>$page", InAppHtmlDocument.document(page, "<s>"))
        }
        val closed = "<!--->\n<!DOCTYPE html><p>y</p>"
        assertEquals("<!--->\n<!DOCTYPE html>$viewport<s><p>y</p>", InAppHtmlDocument.document(closed, "<s>"))
    }

    @Test
    fun anUnterminatedCommentIsLinearAndPrepends() {
        val page = "<!--" + "-".repeat(1_000_000)
        assertEquals("<!doctype html>$viewport<s>$page", InAppHtmlDocument.document(page, "<s>"))
    }

    @Test
    fun alphaIsIgnoredTheSameForHexAndRgba() {
        assertEquals(0xFF112233.toInt(), InAppHtmlDocument.parseColor("#11223380"))
        assertEquals(0xFF112233.toInt(), InAppHtmlDocument.parseColor("rgba(17, 34, 51, 0.5)"))
    }

    @Test
    fun injectsTheHandoffThenTheBridgeInOneScript() {
        val script = InAppHtmlDocument.injection("tok-1", obj("first_name" to "Ana"), obj("sku" to "A1"), "denied")
        assertTrue(script.startsWith("<script>(function(b,p,s){window.__grovs=Object.assign("))
        assertTrue(script.contains("\"token\":\"tok-1\""))
        assertTrue(script.contains("\"first_name\":\"Ana\""))
        assertTrue(script.contains("\"sku\":\"A1\""))
        assertTrue(script.contains("\"pushPermission\":\"denied\""))
        assertTrue(script.contains("{post:function(m){p.call(b,s(m));}}"))
        assertTrue(script.contains("(window.grovsAndroid,window.grovsAndroid.postMessage,JSON.stringify.bind(JSON))"))
        assertTrue(script.endsWith("\n" + InAppBridge.SCRIPT + "</script>"))
    }

    @Test
    fun aValueCannotCloseTheScriptOrBreakAJavaScriptString() {
        val hostile = obj("x" to "</script><script>alert(1)</script>", "line" to "a\u2028b\u2029c")
        val script = InAppHtmlDocument.injection("t", hostile, JsonObject(), "prompt")
        assertEquals("only the closing tag of the boot script", 1, Regex("</script", RegexOption.IGNORE_CASE).findAll(script).count())
        assertFalse(script.contains("\u2028"))
        assertFalse(script.contains("\u2029"))
        assertTrue(script.contains("\\u2028"))
    }

    @Test
    fun valuesJsonCannotCarryAreDroppedAsOnIOS() {
        val values = JsonObject()
        values.addProperty("plan", "pro")
        values.add("nan", JsonPrimitive(Double.NaN))
        values.add("deep", JsonArray().apply { add(1); add(JsonPrimitive(Double.POSITIVE_INFINITY)) })
        values.add("ok", obj("n" to 2))
        assertEquals(setOf("plan", "ok"), InAppHtmlDocument.plain(values).keySet())
    }

    @Test
    fun readsTheThemeColourFromTheHead() {
        assertEquals(0xFF0B1220.toInt(), InAppHtmlDocument.themeColor("<head><meta name=\"theme-color\" content=\"#0B1220\"></head>", dark = false))
        assertEquals(0xFFFFFFFF.toInt(), InAppHtmlDocument.themeColor("<meta content='#fff' name='theme-color'>", dark = false))
        assertEquals(0xFF141428.toInt(), InAppHtmlDocument.themeColor("<meta name=theme-color content=rgb(20,20,40)>", dark = false))
        assertEquals(0xFFFAFAFA.toInt(), InAppHtmlDocument.themeColor("<meta name=\"theme-color\" content=\"rgba(250, 250, 250, 0.5)\">", dark = false))
        assertNull("names are not read", InAppHtmlDocument.themeColor("<meta name=\"theme-color\" content=\"navy\">", dark = false))
        assertNull(InAppHtmlDocument.themeColor("<meta name=\"theme-colorful\" content=\"#000\">", dark = false))
        assertNull("only the head", InAppHtmlDocument.themeColor("<head></head><body><meta name=\"theme-color\" content=\"#000\"></body>", dark = false))
        assertNull(InAppHtmlDocument.themeColor("<p>Hi</p>", dark = false))
    }

    @Test
    fun picksTheThemeColourForTheAppearance() {
        val both = "<head><meta name=\"theme-color\" media=\"(prefers-color-scheme: light)\" content=\"#FFFFFF\">" +
            "<meta name=\"theme-color\" content=\"#0B1220\" media=\"(prefers-color-scheme: dark)\"></head>"
        assertEquals(0xFFFFFFFF.toInt(), InAppHtmlDocument.themeColor(both, dark = false))
        assertEquals(0xFF0B1220.toInt(), InAppHtmlDocument.themeColor(both, dark = true))
        val lightOnly = "<meta name=\"theme-color\" media=\"(prefers-color-scheme: light)\" content=\"#FFFFFF\">"
        assertNull(InAppHtmlDocument.themeColor(lightOnly, dark = true))
        val plain = "<meta name=\"theme-color\" content=\"#0B1220\">"
        assertEquals(0xFF0B1220.toInt(), InAppHtmlDocument.themeColor(lightOnly + plain, dark = true))
    }

    @Test
    fun aTurkishDefaultLocaleReadsTheSame() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale("tr"))
        try {
            val upper = "<META NAME=\"THEME-COLOR\" MEDIA=\"(PREFERS-COLOR-SCHEME: DARK)\" CONTENT=\"#0B1220\">"
            assertEquals(0xFF0B1220.toInt(), InAppHtmlDocument.themeColor(upper, dark = true))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun readsThePageBackgroundAsEvaluateJavascriptReturnsIt() {
        assertEquals(0xFF0A141E.toInt(), InAppHtmlDocument.pageBackground("\"rgb(10, 20, 30)\""))
        assertEquals("a transparent page shows white", InAppHtmlDocument.WHITE, InAppHtmlDocument.pageBackground("\"rgba(0, 0, 0, 0)\""))
        assertNull(InAppHtmlDocument.pageBackground("null"))
        assertNull(InAppHtmlDocument.pageBackground(null))
        assertNull(InAppHtmlDocument.pageBackground("not json"))
    }

    @Test
    fun luminanceDecidesTheStatusBarIcons() {
        assertTrue(InAppHtmlDocument.isLight(0xFFFFFFFF.toInt()))
        assertFalse(InAppHtmlDocument.isLight(0xFF0B1220.toInt()))
        assertFalse(InAppHtmlDocument.isLight(0xFF000000.toInt()))
    }

    @Test
    fun clampsTheHeightToTheLayout() {
        assertEquals(720f, InAppHtmlDocument.clamp(2000f, InAppLayout.MODAL, 800f)!!, 0.01f)
        assertEquals(300f, InAppHtmlDocument.clamp(300f, InAppLayout.MODAL, 800f)!!, 0.01f)
        assertEquals(240f, InAppHtmlDocument.clamp(2000f, InAppLayout.BANNER_TOP, 800f)!!, 0.01f)
        assertEquals(100f, InAppHtmlDocument.clamp(100f, InAppLayout.BANNER_BOTTOM, 800f)!!, 0.01f)
        assertNull(InAppHtmlDocument.clamp(100f, InAppLayout.FULL, 800f))
        assertEquals("landscape phone: 45 %", 180f, InAppHtmlDocument.clamp(2000f, InAppLayout.BANNER_BOTTOM, 400f)!!, 0.01f)
        assertEquals(44f, InAppHtmlDocument.clamp(1f, InAppLayout.MODAL, 800f)!!, 0.01f)
        assertEquals(44f, InAppHtmlDocument.clamp(1f, InAppLayout.BANNER_TOP, 800f)!!, 0.01f)
        assertEquals(44f, InAppHtmlDocument.clamp(100f, InAppLayout.BANNER_BOTTOM, 20f)!!, 0.01f)
    }

    @Test
    fun openablePercentEncodesWhatAURLCannotHoldRaw() {
        assertEquals("mailto:a@b.com?subject=Hi%20there", InAppHtmlDocument.openable("mailto:a@b.com?subject=Hi there"))
        assertEquals("https://a.example/%C3%A9#x%20y", InAppHtmlDocument.openable("https://a.example/é#x y"))
        assertEquals("https://a.example/a%20b", InAppHtmlDocument.openable("https://a.example/a%20b"))
        assertEquals("myapp://again", InAppHtmlDocument.openable("myapp://again"))
        assertNull("no host once encoded", InAppHtmlDocument.openable("https://[::1"))
    }

    @Test
    fun knowsWhenAPageAsksForCover() {
        assertTrue(InAppHtmlDocument.hasCover("<meta name=viewport content=\"width=device-width, VIEWPORT-FIT=COVER\">"))
        assertFalse(InAppHtmlDocument.hasCover("<p>Hi</p>"))
    }
}
