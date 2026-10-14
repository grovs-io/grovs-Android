package io.grovs.e2e.contract

import io.grovs.Grovs
import io.grovs.e2e.DrivenBackendTestBase
import io.grovs.e2e.E2ETestUtils
import io.grovs.handlers.GrovsContext
import io.grovs.handlers.GrovsManager
import org.junit.After
import org.junit.Before

/** A driven backend that answers like the real one; every test fails if the SDK broke the contract. */
internal abstract class ContractTestBase : DrivenBackendTestBase() {

    protected lateinit var contract: ContractBackend
    protected val deepLinks = RecordingDeepLinkListener()

    @Before
    fun installContract() {
        contract = ContractBackend(backend).also { it.install() }
        Grovs.setOnDeeplinkReceivedListener(null, deepLinks)
    }

    @After
    fun assertContractKept() = contract.assertKept()

    protected fun pushOpens(phase: String? = null): Int =
        if (phase == null) backend.count("push_open") else backend.count("push_open", phase)

    /**
     * Moves virtual time, then gives the real network a moment so replies are in before asserting.
     * Positive checks should wait on a condition; this only widens the window before negative checks.
     */
    protected fun settle(totalMs: Long) {
        advance(totalMs)
        repeat(3) { Thread.sleep(50); pump() }
    }

    /** Configures, starts the launcher and waits for authentication. */
    protected fun authenticated() {
        configure()
        foreground()
        pumpUntil("authentication") { manager().authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED }
        pump()
    }

    /**
     * The process dies and starts again: the SDK singleton and its context are rebuilt while the
     * app's stored preferences stay, as on a real cold start.
     */
    protected fun simulateProcessDeath() {
        background()
        E2ETestUtils.resetGrovsSingleton()
        context = GrovsContext(serialDispatcher = driver)
        E2ETestUtils.installGrovsContext(context)
        Grovs.setOnDeeplinkReceivedListener(null, deepLinks)
    }
}
