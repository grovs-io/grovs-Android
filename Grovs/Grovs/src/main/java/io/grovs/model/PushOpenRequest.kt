package io.grovs.model

import com.google.gson.annotations.SerializedName

internal class PushOpenRequest(
    @SerializedName("campaign_id")
    val campaignId: String,
    @SerializedName("session_id")
    val sessionId: String,
)
