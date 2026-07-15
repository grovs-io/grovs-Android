package io.grovs.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL
import java.util.Date
import java.util.UUID

class CustomEventTest {

    @Test
    fun `reserved names are rejected`() {
        listOf(
            "view", "open", "install", "reinstall", "app_open",
            "time_spent", "reactivation", "user_referred", "custom", "screen_view",
        ).forEach { reserved ->
            assertTrue("$reserved should be reserved", CustomEventRules.isReserved(reserved))
            assertFalse("$reserved should be invalid", CustomEventRules.isValidName(reserved))
        }
    }

    @Test
    fun `reserved name check is case insensitive`() {
        assertTrue(CustomEventRules.isReserved("APP_OPEN"))
        assertTrue(CustomEventRules.isReserved("Screen_View"))
    }

    @Test
    fun `ordinary names are valid`() {
        assertTrue(CustomEventRules.isValidName("checkout_completed"))
        assertTrue(CustomEventRules.isValidName("Added To Cart"))
    }

    @Test
    fun `blank names are invalid`() {
        assertFalse(CustomEventRules.isValidName(""))
        assertFalse(CustomEventRules.isValidName("   "))
    }

    @Test
    fun `NaN and Infinity properties are dropped per key`() {
        val sanitized = CustomEventRules.sanitizeProperties(
            mapOf(
                "good" to 1.5,
                "nan" to Double.NaN,
                "posInf" to Double.POSITIVE_INFINITY,
                "negInf" to Float.NEGATIVE_INFINITY,
            )
        )!!

        assertEquals(setOf("good"), sanitized.keys)
    }

    @Test
    fun `Date URL and UUID properties are coerced to strings`() {
        val uuid = UUID.randomUUID()
        val sanitized = CustomEventRules.sanitizeProperties(
            mapOf(
                "date" to Date(0),
                "url" to URL("https://grovs.io"),
                "uuid" to uuid,
            )
        )!!

        assertTrue(sanitized["date"] is String)
        assertEquals("https://grovs.io", sanitized["url"])
        assertEquals(uuid.toString(), sanitized["uuid"])
    }

    @Test
    fun `properties over 8KB are dropped entirely`() {
        val huge = mapOf("blob" to "x".repeat(CustomEventRules.MAX_PROPERTIES_BYTES + 1))
        assertNull(CustomEventRules.sanitizeProperties(huge))
    }

    @Test
    fun `null properties stay null`() {
        assertNull(CustomEventRules.sanitizeProperties(null))
    }

    @Test
    fun `tags are capped at 20`() {
        val tags = (1..30).map { "tag$it" }
        assertEquals(CustomEventRules.MAX_TAGS, CustomEventRules.sanitizeTags(tags)!!.size)
    }

    @Test
    fun `over-long tags are truncated to 255 chars`() {
        val sanitized = CustomEventRules.sanitizeTags(listOf("y".repeat(300)))!!
        assertEquals(CustomEventRules.MAX_TAG_LENGTH, sanitized.first().length)
    }

    @Test
    fun `blank tags are dropped`() {
        assertEquals(listOf("real"), CustomEventRules.sanitizeTags(listOf("real", "", "   ")))
    }

    @Test
    fun `global tags merge with event tags and dedupe, respecting the cap`() {
        val merged = CustomEventRules.mergeTags(
            eventTags = listOf("a", "b"),
            globalTags = listOf("b", "c"),
        )!!

        assertEquals(listOf("a", "b", "c"), merged)
    }

    @Test
    fun `merged tags are capped at 20 combined`() {
        val merged = CustomEventRules.mergeTags(
            eventTags = (1..15).map { "e$it" },
            globalTags = (1..15).map { "g$it" },
        )!!

        assertEquals(CustomEventRules.MAX_TAGS, merged.size)
    }

    @Test
    fun `dedupe happens before cap, not after`() {
        // e1..e10 overlap between event/global tags; deduping before the 20-item cap (not after)
        // is required to end up with all 20 distinct tags.
        val merged = CustomEventRules.mergeTags(
            eventTags = (1..15).map { "e$it" },
            globalTags = (1..10).map { "e$it" } + (1..5).map { "g$it" },
        )!!

        assertEquals(20, merged.size)
        assertTrue("Should contain all event tags", merged.containsAll((1..15).map { "e$it" }))
        assertTrue("Should contain all global unique tags", merged.containsAll((1..5).map { "g$it" }))
    }

    @Test
    fun `properties exceeding 8KB byte count are dropped even if char count is under limit`() {
        // Emoji are 4 bytes each in UTF-8, so 2050 of them (8200 bytes) exceed the 8192-byte
        // limit while the char count (2050) stays under it.
        val emoji = "🎉"
        val manyEmojis = emoji.repeat(2050)

        assertTrue("Character count should be under 8192", manyEmojis.length < 8192)
        assertTrue("Byte count should exceed 8192", manyEmojis.toByteArray(Charsets.UTF_8).size > 8192)

        val sanitized = CustomEventRules.sanitizeProperties(mapOf("emoji_blob" to manyEmojis))
        assertNull("Properties should be dropped when byte count exceeds 8KB", sanitized)
    }
}
