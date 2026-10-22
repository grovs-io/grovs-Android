package io.grovs.example.actions

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TestRunnerTest {

    private var now = 0L
    private val log = TestLog { now }
    private val runner = TestRunner(log) { now }

    @Test
    fun logsSuccessWithTheActionAttached() = runTest {
        val action = TestAction(LogType.EVENT, "track demo", "track()") {
            now += 40
            ActionResult(LogState.OK, "sent")
        }
        val outcome = runner.run(action)
        assertEquals(40L, outcome.elapsedMs)
        assertEquals("sent · 40 ms", outcome.entry.detail)
        assertSame(action, outcome.entry.action)
        assertEquals(LogState.OK, log.entries.value.single().state)
    }

    @Test
    fun turnsExceptionsIntoErrorEntries() = runTest {
        val action = TestAction(LogType.INSPECT, "linkDetails", "linkDetails()") { error("404 not found") }
        val outcome = runner.run(action)
        assertEquals(LogState.ERROR, outcome.result.state)
        assertEquals("404 not found", outcome.entry.detail)
    }

    @Test
    fun runningAgainAddsAnotherEntryForTheSameAction() = runTest {
        val action = TestAction(LogType.EVENT, "track", "track()") { ActionResult(LogState.OK, "sent") }
        runner.run(action)
        runner.run(log.entries.value.first().action!!)
        assertEquals(2, log.entries.value.size)
        assertSame(log.entries.value[0].action, log.entries.value[1].action)
    }

    @Test
    fun resultCodeReplacesActionCode() = runTest {
        val action = TestAction(LogType.CREATE, "generate", "generateLink()") { ActionResult(LogState.OK, "ok", "L", "generateLink()\n\n-> \"L\"") }
        assertEquals("generateLink()\n\n-> \"L\"", runner.run(action).entry.code)
    }
}
