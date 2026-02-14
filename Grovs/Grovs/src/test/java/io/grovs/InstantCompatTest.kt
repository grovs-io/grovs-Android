package io.grovs

import io.grovs.TestAssertions.assertEqualsWithContext
import io.grovs.TestAssertions.assertNotNullWithContext
import io.grovs.TestAssertions.assertTrueWithContext
import io.grovs.TestAssertions.assertFalseWithContext
import io.grovs.utils.InstantCompat
import org.junit.Assert.*
import org.junit.Test

/**
 * Core tests for InstantCompat utility class.
 */
class InstantCompatTest {

    @Test
    fun `InstantCompat now returns epochMillis between before and after system time`() {
        val before = System.currentTimeMillis()
        val instant = InstantCompat.now()
        val after = System.currentTimeMillis()

        assertTrueWithContext(
            instant.epochMillis >= before,
            "epochMillis >= before",
            "after InstantCompat.now() called between $before and $after"
        )
        assertTrueWithContext(
            instant.epochMillis <= after,
            "epochMillis <= after",
            "after InstantCompat.now() called between $before and $after"
        )
    }

    @Test
    fun `InstantCompat ofEpochMilli creates instant with correct epochMillis and toEpochMilli`() {
        val millis = 1702656000000L

        val instant = InstantCompat.ofEpochMilli(millis)

        assertEqualsWithContext(
            millis,
            instant.epochMillis,
            "epochMillis",
            "after InstantCompat.ofEpochMilli($millis)"
        )
        assertEqualsWithContext(
            millis,
            instant.toEpochMilli(),
            "toEpochMilli()",
            "after InstantCompat.ofEpochMilli($millis)"
        )
    }

    @Test
    fun `InstantCompat parse creates instant from ISO 8601 string and toIsoString returns same format`() {
        val iso = "2023-12-15T12:00:00.000Z"

        val instant = InstantCompat.parse(iso)

        assertNotNullWithContext(
            instant,
            "parsed instant",
            "after InstantCompat.parse('$iso')"
        )
        assertEqualsWithContext(
            iso,
            instant.toIsoString(),
            "toIsoString()",
            "after parsing ISO string '$iso'"
        )
    }

    @Test
    fun `InstantCompat plusMillis adds milliseconds correctly`() {
        val instant = InstantCompat.ofEpochMilli(1000)

        val result = instant.plusMillis(500)

        assertEqualsWithContext(
            1500L,
            result.epochMillis,
            "epochMillis",
            "after 1000ms + 500ms"
        )
    }

    @Test
    fun `InstantCompat isBefore and isAfter return correct comparison results`() {
        val earlier = InstantCompat.ofEpochMilli(1000)
        val later = InstantCompat.ofEpochMilli(2000)

        assertTrueWithContext(
            earlier.isBefore(later),
            "1000ms.isBefore(2000ms)",
            "comparing earlier to later instant"
        )
        assertTrueWithContext(
            later.isAfter(earlier),
            "2000ms.isAfter(1000ms)",
            "comparing later to earlier instant"
        )
        assertFalseWithContext(
            earlier.isBefore(earlier),
            "1000ms.isBefore(1000ms)",
            "comparing instant to itself"
        )
    }
}
