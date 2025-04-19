package io.grovs.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import io.grovs.model.AppDetails
import java.util.Locale

fun getDeviceName(): String =
    if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) {
        Build.MODEL
    } else {
        "${Build.MANUFACTURER} ${Build.MODEL}"
    }.capitalize(Locale.ROOT)

class AppDetailsHelper constructor(private val context: Context) {

    var versionName: String = context.packageManager.getPackageInfo(context.packageName, 0).versionName
    var versionCode: Int = context.packageManager.getPackageInfo(context.packageName, 0).versionCode
    var applicationId = context.packageName
    @SuppressLint("HardwareIds")
    var deviceID = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    var device = getDeviceName()

    fun toAppDetails(): AppDetails {
        return AppDetails(version = versionName,
            build = versionCode.toString(),
            bundle =  applicationId,
            device = device,
            deviceID = deviceID,
            userAgent = WebViewUtils.getUserAgent(context)
        )
    }
}