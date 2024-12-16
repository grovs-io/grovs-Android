package io.grovs.model

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize
import java.time.Instant

enum class EventType {
    @SerializedName("app_open")
    APP_OPEN,
    @SerializedName("view")
    VIEW,
    @SerializedName("open")
    OPEN,
    @SerializedName("install")
    INSTALL,
    @SerializedName("reinstall")
    REINSTALL,
    @SerializedName("time_spent")
    TIME_SPENT,
    @SerializedName("reactivation")
    REACTIVATION
}

@Parcelize
class Event(
    /// The type of the event.
    val event: EventType,
    /// The creation date of the event.
    @SerializedName("created_at")
    val createdAt: Instant,
    /// The link associated with the event.
    var link: String? = null,
    /// The engagement time associated with the event.
    @SerializedName("engagement_time")
    var engagementTime: Int? = null
) : Parcelable {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Event) return false

        return event == other.event && createdAt == other.createdAt
    }

    override fun toString(): String {
        return "Event(event=$event, createdAt=$createdAt, link=$link, engagementTime=$engagementTime)"
    }

}