package io.grovs

import io.grovs.TestAssertions.assertEqualsWithContext
import io.grovs.TestAssertions.assertTrueWithContext
import io.grovs.utils.DurationCompat
import io.grovs.utils.InstantCompat
import org.junit.Assert.*
import org.junit.Test

/**
 * Core tests for DurationCompat utility class.
 */
class DurationCompatTest {

    @Test
    fun `DurationCompat ofSeconds creates duration with correct milliseconds and seconds`() {
        val duration = DurationCompat.ofSeconds(5)

        assertEqualsWithContext(
            5000L,
            duration.toMillis(),
            "toMillis()",
            "after DurationCompat.ofSeconds(5)"
        )
        assertEqualsWithContext(
            5L,
            duration.toSeconds(),
            "toSeconds()",
            "after DurationCompat.ofSeconds(5)"
        )
    }

    @Test
    fun `DurationCompat between calculates correct duration from start to end InstantCompat`() {
        val start = InstantCompat.ofEpochMilli(1000)
        val end = InstantCompat.ofEpochMilli(6000)

        val duration = DurationCompat.between(start, end)

        assertEqualsWithContext(
            5000L,
            duration.toMillis(),
            "toMillis()",
            "after DurationCompat.between(start=1000ms, end=6000ms)"
        )
    }

    @Test
    fun `DurationCompat plus operator adds two durations correctly`() {
        val d1 = DurationCompat.ofSeconds(10)
        val d2 = DurationCompat.ofSeconds(5)

        val result = d1 + d2

        assertEqualsWithContext(
            15_000L,
            result.toMillis(),
            "toMillis()",
            "after 10s + 5s"
        )
    }

    @Test
    fun `DurationCompat minus operator subtracts two durations correctly`() {
        val d1 = DurationCompat.ofSeconds(10)
        val d2 = DurationCompat.ofSeconds(3)

        val result = d1 - d2

        assertEqualsWithContext(
            7_000L,
            result.toMillis(),
            "toMillis()",
            "after 10s - 3s"
        )
    }

    @Test
    fun `DurationCompat compareTo returns correct ordering for shorter, longer, and equal durations`() {
        val shorter = DurationCompat.ofSeconds(5)
        val longer = DurationCompat.ofSeconds(10)
        val equal = DurationCompat.ofSeconds(5)

        assertTrueWithContext(
            shorter < longer,
            "5s < 10s",
            "comparing shorter to longer duration"
        )
        assertTrueWithContext(
            longer > shorter,
            "10s > 5s",
            "comparing longer to shorter duration"
        )
        assertEqualsWithContext(
            0,
            shorter.compareTo(equal),
            "compareTo result",
            "comparing equal durations (5s to 5s)"
        )
    }
}
