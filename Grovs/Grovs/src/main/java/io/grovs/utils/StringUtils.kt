package io.grovs.utils

import android.util.Patterns

fun String.isValidUrl(): Boolean {
    return Patterns.WEB_URL.matcher(this).matches()
}