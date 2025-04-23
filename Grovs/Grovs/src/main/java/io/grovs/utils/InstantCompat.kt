package io.grovs.utils

import android.os.Build
import android.os.Parcelable
import kotlinx.android.parcel.Parcelize
import java.text.ParseException
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Parcelize
data class InstantCompat(val epochMillis: Long) : Parcelable, Comparable<InstantCompat> {

    companion object {
        fun now(): InstantCompat {
            return InstantCompat(System.currentTimeMillis())
        }

        fun ofEpochMilli(millis: Long): InstantCompat {
            return InstantCompat(millis)
        }

        fun ofEpochSecond(seconds: Long, nanosAdjustment: Long = 0): InstantCompat {
            val millis = seconds * 1_000 + nanosAdjustment / 1_000_000
            return InstantCompat(millis)
        }

        fun parse(iso8601: String): InstantCompat {
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                parseUsingJavaTime(iso8601)
            } else {
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val adjusted = iso8601.replace("Z", "+0000").replace(":", "")
                val date = sdf.parse(adjusted)
                    ?: throw IllegalArgumentException("Invalid ISO 8601 string: $iso8601")
                InstantCompat(date.time)
            }
        }

        private fun parseUsingJavaTime(iso8601: String): InstantCompat {
            val instantClass = Class.forName("java.time.Instant")
            val parseMethod = instantClass.getMethod("parse", CharSequence::class.java)
            val instantObj = parseMethod.invoke(null, iso8601)
            val toEpochMilliMethod = instantClass.getMethod("toEpochMilli")
            val epochMillis = toEpochMilliMethod.invoke(instantObj) as Long
            return InstantCompat(epochMillis)
        }
    }

    fun toEpochMilli(): Long = epochMillis

    fun toIsoString(): String {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            toIsoUsingJavaTime()
        } else {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.format(Date(epochMillis))
        }
    }

    fun toDate(): Date = Date(epochMillis)

    private fun toIsoUsingJavaTime(): String {
        val instantClass = Class.forName("java.time.Instant")
        val ofEpochMilliMethod = instantClass.getMethod("ofEpochMilli", Long::class.javaPrimitiveType)
        val instantObj = ofEpochMilliMethod.invoke(null, epochMillis)
        return instantObj.toString()
    }

    fun plusMillis(millisToAdd: Long): InstantCompat {
        return InstantCompat(epochMillis + millisToAdd)
    }

    fun minusMillis(millisToSubtract: Long): InstantCompat {
        return InstantCompat(epochMillis - millisToSubtract)
    }

    fun isBefore(other: InstantCompat): Boolean {
        return this.epochMillis < other.epochMillis
    }

    fun isAfter(other: InstantCompat): Boolean {
        return this.epochMillis > other.epochMillis
    }

    override fun toString(): String = toIsoString()

    override fun compareTo(other: InstantCompat): Int {
        return this.epochMillis.compareTo(other.epochMillis)
    }
}