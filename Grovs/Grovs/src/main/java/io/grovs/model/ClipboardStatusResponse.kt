package io.grovs.model

import com.google.gson.annotations.SerializedName

/** Response of `POST clipboard_status`. `clipboardActive` is null when the backend omitted the field. */
class ClipboardStatusResponse(
    @SerializedName("clipboard_active")
    val clipboardActive: Boolean?,
)
