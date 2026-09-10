package io.grovs.model

import com.google.gson.annotations.SerializedName

/// Body of POST events/batch. Items are `Event` or `CustomEvent`; Gson serialises each by its
/// runtime type, so one request class serves both kinds.
data class BatchEventsRequest(
    @SerializedName("events")
    val events: List<Any>,
)

data class BatchEventError(
    @SerializedName("index")
    val index: Int,
    @SerializedName("error")
    val error: String,
)

/// A 2xx means the backend consumed the batch. Rejected items are reported here and are never
/// going to be accepted, so the caller drops the whole batch from storage either way.
data class BatchEventsResponse(
    @SerializedName("accepted")
    val accepted: Int,
    @SerializedName("rejected")
    val rejected: Int,
    @SerializedName("errors")
    private val rawErrors: List<BatchEventError>? = null,
) {
    val errors: List<BatchEventError> get() = rawErrors ?: emptyList()
}
