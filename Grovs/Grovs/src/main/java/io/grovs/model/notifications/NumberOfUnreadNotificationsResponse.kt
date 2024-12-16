package io.grovs.model.notifications

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
data class NumberOfUnreadNotificationsResponse (
    @SerializedName("number_of_unread_notifications")
    var numberOfUnreadNotifications: Int
) : Parcelable {
}