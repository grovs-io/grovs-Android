package io.grovs.handlers

import android.app.Activity

interface ActivityProvider {
    fun requireActivity(): Activity?
}
