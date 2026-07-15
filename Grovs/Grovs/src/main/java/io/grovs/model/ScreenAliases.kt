package io.grovs.model

import com.google.gson.annotations.SerializedName

data class ScreenAlias(
    @SerializedName("identifier")
    val identifier: String,
    @SerializedName("alias")
    val alias: String,
)

data class ScreenAliasesRequest(
    @SerializedName("screen_aliases")
    val screenAliases: List<ScreenAlias>,
)
