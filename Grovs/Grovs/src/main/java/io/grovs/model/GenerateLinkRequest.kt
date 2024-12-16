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
    val tags: String?
) : Parcelable {
}

@Parcelize
class GenerateLinkResponse(
    val link: String
) : Parcelable {
}