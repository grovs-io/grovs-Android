package io.grovs.example.actions

import kotlin.coroutines.cancellation.CancellationException

data class RunOutcome(val result: ActionResult, val entry: LogEntry, val elapsedMs: Long, val error: Throwable? = null)

class TestRunner(
    private val log: TestLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(action: TestAction): RunOutcome {
        val start = clock()
        var error: Throwable? = null
        val result = try {
            action.run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e
            ActionResult(LogState.ERROR, e.message ?: e.toString(), code = "${action.code}\n\n$e")
        }
        val elapsed = clock() - start
        val detail = if (result.state == LogState.ERROR) result.detail else "${result.detail} · $elapsed ms"
        val entry = log.add(action.type, result.state, action.title, detail, result.code ?: action.code, action)
        return RunOutcome(result, entry, elapsed, error)
    }
}
