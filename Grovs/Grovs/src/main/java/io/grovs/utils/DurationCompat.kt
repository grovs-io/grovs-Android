package io.grovs.utils

class DurationCompat private constructor(private val millis: Long) {

    companion object {
        fun between(startInclusive: InstantCompat, endExclusive: InstantCompat): DurationCompat {
            return DurationCompat(endExclusive.toEpochMilli() - startInclusive.toEpochMilli())
        }

        fun ofMillis(millis: Long): DurationCompat = DurationCompat(millis)
        fun ofSeconds(seconds: Long): DurationCompat = DurationCompat(seconds * 1_000)
        fun ofMinutes(minutes: Long): DurationCompat = DurationCompat(minutes * 60 * 1_000)
        fun ofHours(hours: Long): DurationCompat = DurationCompat(hours * 60 * 60 * 1_000)
        fun ofDays(days: Long): DurationCompat = DurationCompat(days * 24 * 60 * 60 * 1_000)
    }

    // Duration components
    val seconds: Long get() = millis / 1_000
    fun toMillis(): Long = millis
    fun toSeconds(): Long = seconds
    fun toMinutes(): Long = millis / (60 * 1_000)
    fun toHours(): Long = millis / (60 * 60 * 1_000)
    fun toDays(): Long = millis / (24 * 60 * 60 * 1_000)

    // Math
    operator fun plus(other: DurationCompat): DurationCompat = DurationCompat(this.millis + other.millis)
    operator fun minus(other: DurationCompat): DurationCompat = DurationCompat(this.millis - other.millis)
    operator fun compareTo(other: DurationCompat): Int = this.millis.compareTo(other.millis)

    override fun toString(): String = "${toSeconds()}s (${millis}ms)"
    override fun equals(other: Any?): Boolean = other is DurationCompat && other.millis == millis
    override fun hashCode(): Int = millis.hashCode()
}