package io.grovs.model.notifications

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize
import java.time.Instant

@Parcelize
data class NotificationsRequest (
    var page: Int
) : Parcelable {
}

@Parcelize
data class NotificationsResponse (
    val notifications: List<Notification>?  // List of notifications.
) : Parcelable {
}

@Parcelize
data class Notification(
    val id: Int,                                 // Unique identifier for the notification.
    val title: String,                           // The title of the notification.
    @SerializedName("updated_at")
    val updatedAt: Instant, // The date when the notification was last updated.
    val subtitle: String?,                       // An optional subtitle for the notification.
    @SerializedName("auto_display")
    val autoDisplay: Boolean, // Indicates whether the notification should be displayed automatically.
    @SerializedName("access_url")
    val accessURL: String?,     // An optional URL associated with the notification.
    var read: Boolean                            // Indicates whether the notification has been read.
) : Parcelable