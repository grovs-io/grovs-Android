package io.grovs.inapp

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Loads src/test/resources/in-app-shared, the contract copied from the backend. */
internal object InAppShared {
    fun bytes(name: String): ByteArray {
        val stream = InAppShared::class.java.classLoader!!.getResourceAsStream("in-app-shared/$name")
            ?: error("missing in-app-shared/$name: copy it from the backend")
        return stream.use { it.readBytes() }
    }

    fun text(name: String): String = bytes(name).toString(Charsets.UTF_8)

    fun json(name: String): JsonObject = JsonParser.parseString(text(name)).asJsonObject

    fun obj(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    /** Objects merge key by key; arrays and scalars, null included, replace. */
    fun merge(base: JsonObject, over: JsonObject): JsonObject {
        val out = base.deepCopy()
        for ((key, value) in over.entrySet()) {
            val under = base.get(key)
            if (under != null && under.isJsonObject && value.isJsonObject) {
                out.add(key, merge(under.asJsonObject, value.asJsonObject))
            } else {
                out.add(key, value.deepCopy())
            }
        }
        return out
    }

    fun baseCampaign(): JsonObject = json("cases.json").getAsJsonObject("base_campaign")
}
