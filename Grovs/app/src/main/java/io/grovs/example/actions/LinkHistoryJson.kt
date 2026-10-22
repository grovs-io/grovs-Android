package io.grovs.example.actions

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

fun encodeLinkEntries(entries: List<LogEntry>): String = JSONArray().also { array ->
    entries.forEach { e ->
        array.put(
            JSONObject()
                .put("time", e.time)
                .put("title", e.title)
                .put("detail", e.detail)
                .put("code", e.code)
                .put("link", e.link ?: JSONObject.NULL)
                .put("sources", JSONArray(e.sources.map { it.name })),
        )
    }
}.toString()

fun decodeLinkEntries(json: String?): List<LogEntry> {
    if (json == null) return emptyList()
    return try {
        val array = JSONArray(json)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val sources = o.getJSONArray("sources")
            LogEntry(
                id = 0,
                time = o.getLong("time"),
                type = LogType.LINK,
                state = LogState.OK,
                title = o.getString("title"),
                detail = o.getString("detail"),
                code = o.getString("code"),
                link = if (o.isNull("link")) null else o.getString("link"),
                sources = (0 until sources.length()).map { LinkSource.valueOf(sources.getString(it)) }.toSet(),
            )
        }
    } catch (e: JSONException) {
        emptyList()
    }
}
