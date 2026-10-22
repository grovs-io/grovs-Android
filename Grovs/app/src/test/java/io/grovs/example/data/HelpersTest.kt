package io.grovs.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpersTest {

    @Test
    fun extractsLinkPath() {
        assertEquals("x7Kp2a", extractLinkPath("https://grovs.link/x7Kp2a"))
        assertEquals("x7Kp2a", extractLinkPath("grovs.link/x7Kp2a"))
        assertEquals("x7Kp2a", extractLinkPath("  x7Kp2a  "))
        assertEquals("b", extractLinkPath("https://grovs.link/a/b/?utm=1#top"))
        assertNull(extractLinkPath("https://grovs.link/"))
        assertNull(extractLinkPath("https://grovs.link"))
        assertNull(extractLinkPath("   "))
    }

    @Test
    fun checksWebUrls() {
        assertTrue(isWebUrl("https://grovs.link/x"))
        assertTrue(isWebUrl("http://a.b"))
        assertFalse(isWebUrl("grovs.link/x"))
        assertFalse(isWebUrl("https://"))
    }

    @Test
    fun buildsAdbCommand() {
        assertEquals(
            "adb shell am start -a android.intent.action.VIEW -d \"https://grovs.link/x\" io.grovs.example",
            adbCommand("https://grovs.link/x", "io.grovs.example"),
        )
    }

    @Test
    fun flattensNestedMaps() {
        val flat = flatten(mapOf("title" to "T", "data" to mapOf("level" to 3, "tags" to listOf("a", "b")), "none" to null))
        assertEquals(listOf("title" to "T", "data.level" to "3", "data.tags" to "a, b", "none" to "null"), flat)
    }

    @Test
    fun parsesAmounts() {
        assertEquals(499, parseAmountToCents("4.99"))
        assertEquals(499, parseAmountToCents("4,99"))
        assertEquals(500, parseAmountToCents("5"))
        assertEquals(10, parseAmountToCents("0.1"))
        assertNull(parseAmountToCents("4.999"))
        assertNull(parseAmountToCents(""))
        assertNull(parseAmountToCents("-1"))
        assertNull(parseAmountToCents("abc"))
    }

    @Test
    fun formatsCentsAndCurrency() {
        assertEquals("4.99", formatCents(499))
        assertEquals("0.05", formatCents(5))
        assertEquals("$", currencySymbol("USD"))
        assertEquals("XYZ1", currencySymbol("XYZ1"))
    }

    @Test
    fun checksEventNames() {
        assertEquals("Enter an event name", eventNameError("  "))
        assertEquals("\"screen_view\" is reserved by the SDK", eventNameError(" screen_view "))
        assertNull(eventNameError("demo_tapped"))
    }

    @Test
    fun checksTags() {
        assertNull(tagsError(List(20) { "t$it" }))
        assertEquals("Up to 20 tags", tagsError(List(21) { "t$it" }))
        assertEquals("Each tag can have up to 255 characters", tagsError(listOf("x".repeat(256))))
    }

    @Test
    fun shortensInTheMiddle() {
        assertEquals("abcde…vwxyz", middleEllipsis("abcdefghijklmnopqrstuvwxyz"))
        assertEquals("short", middleEllipsis("short"))
    }

    @Test
    fun describesRelativeTime() {
        assertEquals("just now", relativeTime(30_000))
        assertEquals("2 min ago", relativeTime(2 * 60_000L + 5_000))
        assertEquals("3 h ago", relativeTime(3 * 3_600_000L))
        assertEquals("2 days ago", relativeTime(2 * 86_400_000L))
    }
}
