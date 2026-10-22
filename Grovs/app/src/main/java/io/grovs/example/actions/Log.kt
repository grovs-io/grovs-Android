package io.grovs.example.actions

enum class LogGroup { LINKS, EVENTS, IN_APP, PUSH, SDK }

enum class LogType(val label: String, val group: LogGroup) {
    LINK("LINK", LogGroup.LINKS),
    CREATE("CREATE", LogGroup.LINKS),
    INSPECT("INSPECT", LogGroup.LINKS),
    EVENT("EVENT", LogGroup.EVENTS),
    SCREEN("SCREEN", LogGroup.EVENTS),
    PURCHASE("PURCHASE", LogGroup.EVENTS),
    IN_APP("IN-APP", LogGroup.IN_APP),
    PUSH("PUSH", LogGroup.PUSH),
    SDK("SDK", LogGroup.SDK),
}

enum class LogState { OK, WARNING, ERROR, NEUTRAL }

enum class LinkSource { LISTENER, FLOW }

enum class LogFilter(val label: String) {
    ALL("All"), LINKS("Links"), IN_APP("In-app"), EVENTS("Events"), PUSH("Push"), ERRORS("Errors");

    fun matches(entry: LogEntry): Boolean = when (this) {
        ALL -> true
        LINKS -> entry.type.group == LogGroup.LINKS
        IN_APP -> entry.type.group == LogGroup.IN_APP
        EVENTS -> entry.type.group == LogGroup.EVENTS
        PUSH -> entry.type.group == LogGroup.PUSH
        ERRORS -> entry.state == LogState.ERROR
    }
}

/** One SDK call with its values, kept by the log so it can be run again. */
class TestAction(
    val type: LogType,
    val title: String,
    val code: String,
    val run: suspend () -> ActionResult,
)

data class ActionResult(
    val state: LogState,
    val detail: String,
    val value: String? = null,
    val code: String? = null,
)

data class LogEntry(
    val id: Long,
    val time: Long,
    val type: LogType,
    val state: LogState,
    val title: String,
    val detail: String,
    val code: String,
    val action: TestAction? = null,
    val link: String? = null,
    val sources: Set<LinkSource> = emptySet(),
)
