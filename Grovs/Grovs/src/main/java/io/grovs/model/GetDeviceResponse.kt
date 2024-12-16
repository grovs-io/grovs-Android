package io.grovs.model

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize
import java.time.Instant

@Parcelize
class GetDeviceResponse(
    @SerializedName("last_seen")
    val lastSeen: Instant?
) : Parcelable {
}