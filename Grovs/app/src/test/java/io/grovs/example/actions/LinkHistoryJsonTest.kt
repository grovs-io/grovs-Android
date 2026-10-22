package io.grovs.example.actions

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkHistoryJsonTest {

    @Test
    fun roundTrip() {
        val entry = LogEntry(
            id = 7, time = 123L, type = LogType.LINK, state = LogState.OK, title = "Opened from link",
            detail = "a · listener", code = "onDeeplinkReceived()", link = "https://grovs.link/a",
            sources = setOf(LinkSource.LISTENER),
        )
        val back = decodeLinkEntries(encodeLinkEntries(listOf(entry))).single()
        assertEquals(entry.copy(id = 0), back)
    }

    @Test
    fun unknownSourceNamesAreSkipped() {
        val json = """[{"time":1,"title":"t","detail":"d","code":"c","link":null,"sources":["LISTENER","PUSH"]}]"""
        assertEquals(setOf(LinkSource.LISTENER), decodeLinkEntries(json).single().sources)
    }

    @Test
    fun badJsonGivesEmptyList() {
        assertEquals(emptyList<LogEntry>(), decodeLinkEntries("nope"))
        assertEquals(emptyList<LogEntry>(), decodeLinkEntries(null))
    }
}
