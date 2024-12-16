package io.grovs.utils

import android.content.Context

fun Context.hasURISchemesConfigured(): Boolean {
    // This check is not possible on android
    return true
}
