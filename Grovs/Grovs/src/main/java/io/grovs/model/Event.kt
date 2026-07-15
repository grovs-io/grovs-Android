package io.grovs.model

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import io.grovs.utils.InstantCompat
import kotlinx.parcelize.Parcelize
import java.time.Instant
import java.util.UUID

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
    val createdAt: InstantCompat,
    /// The link associated with the event.
    var link: String? = null,
    /// The engagement time associated with the event.
    @SerializedName("engagement_time")
    var engagementTime: Int? = null,
    /// Idempotency key. The backend dedupes on this.
    @SerializedName("event_id")
    val eventId: String = UUID.randomUUID().toString(),
    /// The session the event was created in. Immutable, so an event queued across a session
    /// rotation still reports the session it actually belongs to.
    @SerializedName("session_id")
    val sessionId: String? = null
) : Parcelable {

    // Intentionally excludes eventId: EventsStorage.removeEvent() and addOrReplaceEvents()
    // identify events by type + timestamp, and adding eventId here would break both.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Event) return false

        return event == other.event && createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        return 31 * event.hashCode() + createdAt.hashCode()
    }

    override fun toString(): String {
        return "Event(event=$event, createdAt=$createdAt, link=$link, engagementTime=$engagementTime, eventId=$eventId, sessionId=$sessionId)"
    }

}