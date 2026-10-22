package io.grovs.example.data

import org.json.JSONArray
import org.json.JSONObject

/** The settings read by `Grovs.configure`. Changing any of them needs an app restart. */
data class Profile(
    val id: String,
    val name: String,
    val apiKey: String,
    val useTestEnvironment: Boolean,
    val baseUrl: String?,
    val autoTrackScreens: Boolean = true,
    val startEnabled: Boolean = true,
    val clipboardDomains: List<String> = emptyList(),
)

// Replace with your own API key, or add your project in Settings.
val DEFAULT_PROFILES = listOf(
    Profile(
        id = "grovs-test",
        name = "Grovs test",
        apiKey = "grovst_06e36086dad3e934289560e3ca59527282030868f8c844629516c6e6c67bbf1f",
        useTestEnvironment = true,
        baseUrl = null,
    ),
)

fun profileNameError(name: String): String? = if (name.isBlank()) "Enter a name" else null

fun apiKeyError(key: String): String? = if (key.isBlank()) "Enter an API key" else null

/** A blank base URL means the SDK default. */
fun baseUrlError(url: String): String? =
    if (url.isNotBlank() && !isWebUrl(url)) "Must start with http:// or https://" else null

/** Puts the built-in default profile back, replacing a stored profile with the same id. */
fun withDefaultProfile(profiles: List<Profile>): List<Profile> {
    val default = DEFAULT_PROFILES.first()
    return if (profiles.any { it.id == default.id }) profiles.map { if (it.id == default.id) default else it }
    else listOf(default) + profiles
}

fun restartChanges(started: Profile, current: Profile): List<String> {
    if (started.id != current.id) return listOf("Project")
    return buildList {
        if (started.apiKey != current.apiKey) add("API key")
        if (started.useTestEnvironment != current.useTestEnvironment) add("Environment")
        if (started.baseUrl != current.baseUrl) add("Base URL")
        if (started.clipboardDomains != current.clipboardDomains) add("Clipboard domains")
        if (started.autoTrackScreens != current.autoTrackScreens) add("Auto track screens")
        if (started.startEnabled != current.startEnabled) add("Start with SDK enabled")
    }
}

fun encodeProfiles(profiles: List<Profile>): String = JSONArray().also { array ->
    profiles.forEach { p ->
        array.put(
            JSONObject()
                .put("id", p.id)
                .put("name", p.name)
                .put("apiKey", p.apiKey)
                .put("test", p.useTestEnvironment)
                .put("baseUrl", p.baseUrl ?: JSONObject.NULL)
                .put("autoTrack", p.autoTrackScreens)
                .put("startEnabled", p.startEnabled)
                .put("clipboardDomains", JSONArray(p.clipboardDomains)),
        )
    }
}.toString()

fun decodeProfiles(json: String): List<Profile> {
    val array = JSONArray(json)
    return (0 until array.length()).map { i ->
        val o = array.getJSONObject(i)
        val domains = o.optJSONArray("clipboardDomains")
        Profile(
            id = o.getString("id"),
            name = o.getString("name"),
            apiKey = o.getString("apiKey"),
            useTestEnvironment = o.getBoolean("test"),
            baseUrl = if (o.isNull("baseUrl")) null else o.getString("baseUrl"),
            autoTrackScreens = o.optBoolean("autoTrack", true),
            startEnabled = o.optBoolean("startEnabled", true),
            clipboardDomains = if (domains == null) emptyList() else (0 until domains.length()).map(domains::getString),
        )
    }
}
