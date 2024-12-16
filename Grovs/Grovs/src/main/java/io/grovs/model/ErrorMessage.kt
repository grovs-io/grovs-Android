package io.grovs.model

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
class ErrorMessage(
    val error: String
) : Parcelable {
}