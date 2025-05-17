package io.grovs.model

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
data class AppDetails (
    @SerializedName("app_version")
    var version: String,
    var build: String,
    var bundle: String,
    var device: String,
    @SerializedName("vendor_id")
    var deviceID: String,
    @SerializedName("user_agent")
    var userAgent: String,
    var url: String? = null,
    @SerializedName("screen_width")
    var screenWidth: String? = null,
    @SerializedName("screen_height")
    var screenHeight: String? = null,
    var timezone: String? = null,
    var language: String? = null,
    @SerializedName("webgl_vendor")
    var webglVendor: String? = null,
    @SerializedName("webgl_renderer")
    var webglRenderer: String? = null,
) : Parcelable {
}