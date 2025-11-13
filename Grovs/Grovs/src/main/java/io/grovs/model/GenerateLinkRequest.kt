package io.grovs.model

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
class GenerateLinkRequest(
    val title: String?,
    val subtitle: String?,
    @SerializedName("image_url")
    val imageUrl: String?,
    val data: String?,
    val tags: String?,
    @SerializedName("ios_custom_redirect")
    val iosCustomRedirect: CustomLinkRedirect?,
    @SerializedName("android_custom_redirect")
    val androidCustomRedirect: CustomLinkRedirect?,
    @SerializedName("desktop_custom_redirect")
    val desktopCustomRedirect: CustomLinkRedirect?,
    @SerializedName("show_preview_ios")
    val showPreviewIos: Boolean?,
    @SerializedName("show_preview_android")
    val showPreviewAndroid: Boolean?,
    @SerializedName("tracking_campaign")
    val trackingCampaign: String?,
    @SerializedName("tracking_source")
    val trackingSource: String?,
    @SerializedName("tracking_medium")
    val trackingMedium: String?,
    ) : Parcelable {
}

@Parcelize
class CustomLinkRedirect(
    @SerializedName("url")
    val link: String,
    // true: if the app is installed it will handle the link
    // false: even the app is installed the link should be opened in the browser
    @SerializedName("open_app_if_installed")
    val openAppIfInstalled: Boolean = true
) : Parcelable {
}

@Parcelize
class GenerateLinkResponse(
    val link: String
) : Parcelable {
}