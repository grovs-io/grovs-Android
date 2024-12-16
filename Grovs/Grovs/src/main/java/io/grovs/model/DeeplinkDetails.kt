package io.grovs.model

import android.os.Parcelable
import com.google.gson.JsonElement
import kotlinx.parcelize.Parcelize
import java.io.Serializable

class DeeplinkDetails(
    var link: String?,
    var data: Map<String, Object>?
) {
}