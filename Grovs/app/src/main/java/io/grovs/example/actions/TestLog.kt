package io.grovs.example.actions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

interface LinkHistory {
    fun load(): List<LogEntry>
    fun save(entries: List<LogEntry>)

    object None : LinkHistory {
        override fun load() = emptyList<LogEntry>()
        override fun save(entries: List<LogEntry>) = Unit
    }
}

class TestLog(
    private val history: LinkHistory = LinkHistory.None,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val nextId = AtomicLong(1)
    private val _entries = MutableStateFlow(history.load().map { it.copy(id = nextId.getAndIncrement()) })
    val entries: StateFlow<List<LogEntry>> = _entries

    private val _unseenError = MutableStateFlow(false)
    val unseenError: StateFlow<Boolean> = _unseenError

    fun add(
        type: LogType,
        state: LogState,
        title: String,
        detail: String = "",
        code: String = "",
        action: TestAction? = null,
    ): LogEntry {
        val entry = LogEntry(nextId.getAndIncrement(), clock(), type, state, title, detail, code, action)
        _entries.update { (listOf(entry) + it).take(MAX_ENTRIES) }
        if (state == LogState.ERROR) _unseenError.value = true
        return entry
    }

    /** The listener and the flow report the same open separately; both land on one entry. */
    fun recordLinkOpen(
        link: String?,
        data: Map<String, Any?>?,
        tracking: Map<String, Any?>?,
        source: LinkSource,
    ): LogEntry {
        val now = clock()
        lateinit var result: LogEntry
        _entries.update { list ->
            val match = list.firstOrNull {
                it.type == LogType.LINK && it.link == link && source !in it.sources && now - it.time <= MERGE_WINDOW_MS
            }
            if (match != null) {
                result = match.copy(sources = match.sources + source, detail = linkDetail(link, match.sources + source))
                list.map { if (it.id == match.id) result else it }
            } else {
                result = LogEntry(
                    id = nextId.getAndIncrement(),
                    time = now,
                    type = LogType.LINK,
                    state = LogState.OK,
                    title = "Opened from link",
                    detail = linkDetail(link, setOf(source)),
                    code = callCode("onDeeplinkReceived", "link" to link, "data" to data, "tracking" to tracking),
                    link = link,
                    sources = setOf(source),
                )
                (listOf(result) + list).take(MAX_ENTRIES)
            }
        }
        history.save(_entries.value.filter { it.type == LogType.LINK }.take(MAX_LINKS))
        return result
    }

    fun markSeen() {
        _unseenError.value = false
    }

    fun clear() {
        _entries.value = emptyList()
        _unseenError.value = false
        history.save(emptyList())
    }

    private fun linkDetail(link: String?, sources: Set<LinkSource>): String {
        val path = link?.substringAfterLast('/')?.ifBlank { null } ?: "no link"
        return "$path · " + sources.sorted().joinToString(" + ") { it.name.lowercase() }
    }

    companion object {
        const val MAX_ENTRIES = 500
        const val MAX_LINKS = 50
        const val MERGE_WINDOW_MS = 5_000L
    }
}
