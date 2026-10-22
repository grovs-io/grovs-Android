package io.grovs.example.data

import io.grovs.model.CustomEventRules

fun eventNameError(name: String): String? = when {
    name.isBlank() -> "Enter an event name"
    CustomEventRules.isReserved(name) -> "\"${name.trim()}\" is reserved by the SDK"
    else -> null
}

fun tagsError(tags: List<String>): String? = when {
    tags.size > CustomEventRules.MAX_TAGS -> "Up to ${CustomEventRules.MAX_TAGS} tags"
    tags.any { it.length > CustomEventRules.MAX_TAG_LENGTH } ->
        "Each tag can have up to ${CustomEventRules.MAX_TAG_LENGTH} characters"
    else -> null
}
