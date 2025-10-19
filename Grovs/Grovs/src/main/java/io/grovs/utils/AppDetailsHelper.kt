package io.grovs.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import io.grovs.BuildConfig
import io.grovs.model.AppDetails
import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel
import io.grovs.storage.EventsStorage.Companion.GROVS_STORAGE
import java.util.Locale
import java.util.TimeZone

fun getDeviceName(): String =
    if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) {
        Build.MODEL
    } else {
        "${Build.MANUFACTURER} ${Build.MODEL}"
    }.capitalize(Locale.ROOT)

class AppDetailsHelper(private val context: Context) {

    var versionName: String = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
    var versionCode: Int = context.packageManager.getPackageInfo(context.packageName, 0).versionCode
    var applicationId = context.packageName
    @SuppressLint("HardwareIds")
    var deviceID = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    var device = getDeviceName()

    suspend fun toAppDetails(): AppDetails {
        val glInfo = GlUtils.getGlInfoOffscreen()
        val screenSize = ScreenUtils.getScreenResolution(context = context)
        val language = Locale.getDefault().toLanguageTag()
        val timezone = TimeZone.getDefault().id

        return AppDetails(version = versionName,
            build = versionCode.toString(),
            bundle =  applicationId,
            device = device,
            deviceID = deviceID,
            userAgent = WebViewUtils.getUserAgent(context),
            screenWidth = screenSize?.first,
            screenHeight = screenSize?.second,
            timezone = timezone,
            language = language,
            webglVendor = glInfo.vendor,
            webglRenderer = glInfo.renderer
        )
    }
}