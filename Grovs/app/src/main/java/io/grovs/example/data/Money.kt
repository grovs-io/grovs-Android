package io.grovs.example.data

import java.util.Currency
import java.util.Locale

private val AMOUNT = Regex("\\d+([.,]\\d{1,2})?")

fun parseAmountToCents(input: String): Int? {
    val v = input.trim()
    if (!AMOUNT.matches(v)) return null
    val normalized = v.replace(',', '.')
    val whole = normalized.substringBefore('.').toLongOrNull() ?: return null
    val fraction = normalized.substringAfter('.', "").padEnd(2, '0').toInt()
    val cents = whole * 100 + fraction
    return if (cents > Int.MAX_VALUE) null else cents.toInt()
}

fun formatCents(cents: Int): String = String.format(Locale.US, "%d.%02d", cents / 100, cents % 100)

fun currencySymbol(code: String): String = try {
    Currency.getInstance(code).getSymbol(Locale.US)
} catch (e: IllegalArgumentException) {
    code
}
