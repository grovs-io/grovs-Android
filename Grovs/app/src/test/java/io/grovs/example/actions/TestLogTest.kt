package io.grovs.example.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TestLogTest {

    private var now = 1_000_000L
    private val history = FakeHistory()
    private val log = TestLog(history) { now }

    @Test
    fun newestEntryComesFirstAndListIsCapped() {
        repeat(TestLog.MAX_ENTRIES + 5) { log.add(LogType.EVENT, LogState.OK, "e$it") }
        assertEquals(TestLog.MAX_ENTRIES, log.entries.value.size)
        assertEquals("e${TestLog.MAX_ENTRIES + 4}", log.entries.value.first().title)
    }

    @Test
    fun mergesListenerAndFlowForTheSameLink() {
        log.recordLinkOpen("https://grovs.link/a", mapOf("k" to 1), null, LinkSource.LISTENER)
        now += 1_000
        log.recordLinkOpen("https://grovs.link/a", mapOf("k" to 1), null, LinkSource.FLOW)
        val links = log.entries.value
        assertEquals(1, links.size)
        assertEquals(setOf(LinkSource.LISTENER, LinkSource.FLOW), links.first().sources)
    }

    @Test
    fun keepsSeparateOpensApart() {
        log.recordLinkOpen("https://grovs.link/a", null, null, LinkSource.LISTENER)
        now += TestLog.MERGE_WINDOW_MS + 1
        log.recordLinkOpen("https://grovs.link/a", null, null, LinkSource.FLOW)
        log.recordLinkOpen("https://grovs.link/b", null, null, LinkSource.FLOW)
        assertEquals(3, log.entries.value.size)
    }

    @Test
    fun savesLinkHistoryAndLoadsItBack() {
        log.recordLinkOpen("https://grovs.link/a", null, null, LinkSource.LISTENER)
        log.add(LogType.EVENT, LogState.OK, "not a link")
        assertEquals(listOf("https://grovs.link/a"), history.saved.map { it.link })
        val reloaded = TestLog(history) { now }
        assertEquals("https://grovs.link/a", reloaded.entries.value.single().link)
    }

    @Test
    fun errorsAreUnseenUntilMarked() {
        log.add(LogType.EVENT, LogState.OK, "fine")
        assertFalse(log.unseenError.value)
        log.add(LogType.INSPECT, LogState.ERROR, "broken")
        assertTrue(log.unseenError.value)
        log.markSeen()
        assertFalse(log.unseenError.value)
    }

    @Test
    fun clearEmptiesEverything() {
        log.recordLinkOpen("https://grovs.link/a", null, null, LinkSource.LISTENER)
        log.clear()
        assertTrue(log.entries.value.isEmpty())
        assertTrue(history.saved.isEmpty())
    }

    @Test
    fun filtersMatchGroupsAndErrors() {
        val link = log.add(LogType.CREATE, LogState.OK, "a")
        val error = log.add(LogType.EVENT, LogState.ERROR, "b")
        assertTrue(LogFilter.LINKS.matches(link))
        assertFalse(LogFilter.LINKS.matches(error))
        assertTrue(LogFilter.ERRORS.matches(error))
        assertTrue(LogFilter.ALL.matches(link))
    }

    private class FakeHistory : LinkHistory {
        var saved: List<LogEntry> = emptyList()
        override fun load() = saved
        override fun save(entries: List<LogEntry>) { saved = entries }
    }
}
