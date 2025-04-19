package io.grovs.handlers

import android.content.Context
import io.grovs.settings.GrovsSettings
import io.grovs.utils.AppDetailsHelper
import io.grovs.utils.InstantCompat
import io.grovs.utils.WebViewUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.Instant

class GrovsContext {
    @OptIn(ExperimentalCoroutinesApi::class)
    val serialDispatcher = Dispatchers.IO.limitedParallelism(1)
    val settings = GrovsSettings()
    var grovsId: String? = null
    var identifier: String? = null
    var pushToken: String? = null
    var attributes: Map<String, Any>? = null
    var lastSeen: InstantCompat? = null

    fun getAppDetails(context: Context): AppDetailsHelper = AppDetailsHelper(context)
    fun getUserAgent(context: Context): String = WebViewUtils.getUserAgent(context)
}