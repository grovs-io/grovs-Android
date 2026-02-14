package io.grovs

import io.mockk.*
import org.junit.Assert.*

/**
 * Custom assertion helpers that provide detailed context in failure messages.
 * Every assertion includes a 'context' parameter describing the test setup state.
 */
object TestAssertions {

    // ==================== Value Assertions with Context ====================

    /**
     * Assert equality with detailed context.
     * @param expected The expected value
     * @param actual The actual value
     * @param valueName Name of the value being checked (e.g., "grovsId", "authenticationState")
     * @param context Description of the test setup (e.g., "after configure() with valid API key")
     */
    fun <T> assertEqualsWithContext(expected: T, actual: T, valueName: String, context: String) {
        assertEquals(
            "Expected $valueName to be '$expected' $context, but was '$actual'",
            expected,
            actual
        )
    }

    /**
     * Assert value is null with detailed context.
     */
    fun assertNullWithContext(actual: Any?, valueName: String, context: String) {
        assertNull(
            "Expected $valueName to be null $context, but was '$actual'",
            actual
        )
    }

    /**
     * Assert value is not null with detailed context.
     */
    fun assertNotNullWithContext(actual: Any?, valueName: String, context: String) {
        assertNotNull(
            "Expected $valueName to be non-null $context, but was null",
            actual
        )
    }

    /**
     * Assert condition is true with detailed context.
     * @param condition The condition to check
     * @param conditionName Description of what the condition represents (e.g., "SDK is enabled")
     * @param context Description of the test setup
     */
    fun assertTrueWithContext(condition: Boolean, conditionName: String, context: String) {
        assertTrue(
            "Expected '$conditionName' to be true $context, but was false",
            condition
        )
    }

    /**
     * Assert condition is false with detailed context.
     */
    fun assertFalseWithContext(condition: Boolean, conditionName: String, context: String) {
        assertFalse(
            "Expected '$conditionName' to be false $context, but was true",
            condition
        )
    }

    // ==================== State Assertions ====================

    /**
     * Assert GrovsManager is in AUTHENTICATED state.
     */
    fun assertAuthenticated(manager: io.grovs.handlers.GrovsManager, context: String) {
        val actual = manager.authenticationState
        assertEquals(
            "Expected authenticationState to be AUTHENTICATED $context, but was $actual",
            io.grovs.handlers.GrovsManager.AuthenticationState.AUTHENTICATED,
            actual
        )
    }

    /**
     * Assert GrovsManager is in UNAUTHENTICATED state.
     */
    fun assertUnauthenticated(manager: io.grovs.handlers.GrovsManager, context: String) {
        val actual = manager.authenticationState
        assertEquals(
            "Expected authenticationState to be UNAUTHENTICATED $context, but was $actual",
            io.grovs.handlers.GrovsManager.AuthenticationState.UNAUTHENTICATED,
            actual
        )
    }

    /**
     * Assert SDK is enabled.
     */
    fun assertSdkEnabled(grovsContext: io.grovs.handlers.GrovsContext, context: String) {
        assertTrue(
            "Expected SDK to be enabled $context, but sdkEnabled was false",
            grovsContext.settings.sdkEnabled
        )
    }

    /**
     * Assert SDK is disabled.
     */
    fun assertSdkDisabled(grovsContext: io.grovs.handlers.GrovsContext, context: String) {
        assertFalse(
            "Expected SDK to be disabled $context, but sdkEnabled was true",
            grovsContext.settings.sdkEnabled
        )
    }

    /**
     * Assert allowedToSendToBackend state.
     */
    fun assertAllowedToSendToBackend(
        eventsManager: io.grovs.handlers.EventsManager,
        expected: Boolean,
        context: String
    ) {
        val actual = eventsManager.allowedToSendToBackend
        assertEquals(
            "Expected allowedToSendToBackend to be $expected $context, but was $actual",
            expected,
            actual
        )
    }

    // ==================== Callback/Async Assertions ====================

    /**
     * Assert callback was invoked with expected link and no error.
     */
    fun assertCallbackInvokedWithLink(
        link: String?,
        error: Exception?,
        expectedLink: String,
        context: String
    ) {
        assertNull(
            "Expected no error in callback $context, but got: ${error?.message}",
            error
        )
        assertNotNull(
            "Expected link in callback $context, but was null",
            link
        )
        assertEquals(
            "Expected callback link to be '$expectedLink' $context, but was '$link'",
            expectedLink,
            link
        )
    }

    /**
     * Assert callback was invoked with an error containing expected message.
     */
    fun assertCallbackInvokedWithError(
        error: Exception?,
        expectedMessageContains: String,
        context: String
    ) {
        assertNotNull(
            "Expected error in callback $context, but error was null",
            error
        )
        assertTrue(
            "Expected error message to contain '$expectedMessageContains' $context, but was: ${error?.message}",
            error?.message?.contains(expectedMessageContains) == true
        )
    }

    /**
     * Assert CountDownLatch completed within timeout.
     */
    fun assertLatchCompleted(
        latch: java.util.concurrent.CountDownLatch,
        timeoutMs: Long,
        context: String
    ) {
        val completed = latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue(
            "Expected latch to complete within ${timeoutMs}ms $context, but it timed out (count was ${latch.count})",
            completed
        )
    }

    /**
     * Assert a callback was invoked (boolean flag check with timeout context).
     */
    fun assertCallbackInvoked(invoked: Boolean, timeoutMs: Long, context: String) {
        assertTrue(
            "Expected callback to be invoked within ${timeoutMs}ms $context, but it was not",
            invoked
        )
    }

    // ==================== Mock Verification Assertions ====================

    /**
     * Assert an event was sent to the backend via mock service.
     * Wraps coVerify to provide better error messages.
     */
    suspend fun assertEventSent(
        eventType: io.grovs.model.EventType,
        mockService: io.grovs.service.IGrovsService,
        context: String
    ) {
        try {
            coVerify { mockService.addEvent(match { it.event == eventType }) }
        } catch (e: AssertionError) {
            throw AssertionError(
                "Expected $eventType event to be sent to backend $context, but it was not. Original: ${e.message}"
            )
        }
    }

    /**
     * Assert an event was stored in mock storage.
     */
    suspend fun assertEventStored(
        eventType: io.grovs.model.EventType,
        mockStorage: io.grovs.storage.IEventsStorage,
        context: String
    ) {
        try {
            coVerify { mockStorage.addEvent(match { it.event == eventType }) }
        } catch (e: AssertionError) {
            throw AssertionError(
                "Expected $eventType event to be stored $context, but it was not. Original: ${e.message}"
            )
        }
    }

    // PURCHASE_EVENT_DISABLED: /**
    // PURCHASE_EVENT_DISABLED:  * Assert a payment event was stored.
    // PURCHASE_EVENT_DISABLED:  */
    // PURCHASE_EVENT_DISABLED: suspend fun assertPaymentEventStored(
    // PURCHASE_EVENT_DISABLED:     eventType: io.grovs.model.events.PaymentEventType,
    // PURCHASE_EVENT_DISABLED:     mockStorage: io.grovs.storage.IEventsStorage,
    // PURCHASE_EVENT_DISABLED:     context: String
    // PURCHASE_EVENT_DISABLED: ) {
    // PURCHASE_EVENT_DISABLED:     try {
    // PURCHASE_EVENT_DISABLED:         coVerify { mockStorage.addPaymentEvent(match { it.eventType == eventType }) }
    // PURCHASE_EVENT_DISABLED:     } catch (e: AssertionError) {
    // PURCHASE_EVENT_DISABLED:         throw AssertionError(
    // PURCHASE_EVENT_DISABLED:             "Expected $eventType payment event to be stored $context, but it was not. Original: ${e.message}"
    // PURCHASE_EVENT_DISABLED:         )
    // PURCHASE_EVENT_DISABLED:     }
    // PURCHASE_EVENT_DISABLED: }

    /**
     * Assert no events were sent to backend.
     */
    suspend fun assertNoEventsSent(
        mockService: io.grovs.service.IGrovsService,
        context: String
    ) {
        try {
            coVerify(exactly = 0) { mockService.addEvent(any()) }
        } catch (e: AssertionError) {
            throw AssertionError(
                "Expected no events to be sent to backend $context, but some were sent. Original: ${e.message}"
            )
        }
    }

    /**
     * Assert event was removed from storage.
     */
    suspend fun assertEventRemoved(
        mockStorage: io.grovs.storage.IEventsStorage,
        context: String
    ) {
        try {
            coVerify { mockStorage.removeEvent(any()) }
        } catch (e: AssertionError) {
            throw AssertionError(
                "Expected event to be removed from storage $context, but it was not. Original: ${e.message}"
            )
        }
    }

    // ==================== Result Type Assertions ====================

    /**
     * Assert LSResult is Success and return the data.
     */
    fun <T : Any> assertResultSuccess(result: io.grovs.utils.LSResult<T>, context: String): T {
        assertTrue(
            "Expected LSResult.Success $context, but was LSResult.Error: ${(result as? io.grovs.utils.LSResult.Error)?.exception?.message}",
            result is io.grovs.utils.LSResult.Success
        )
        return (result as io.grovs.utils.LSResult.Success).data
    }

    /**
     * Assert LSResult is Error.
     */
    fun <T : Any> assertResultError(result: io.grovs.utils.LSResult<T>, context: String): Exception {
        assertTrue(
            "Expected LSResult.Error $context, but was LSResult.Success",
            result is io.grovs.utils.LSResult.Error
        )
        return (result as io.grovs.utils.LSResult.Error).exception
    }

    /**
     * Assert LSResult is Error with message containing expected text.
     */
    fun <T : Any> assertResultErrorContains(
        result: io.grovs.utils.LSResult<T>,
        expectedMessageContains: String,
        context: String
    ) {
        val error = assertResultError(result, context)
        assertTrue(
            "Expected error message to contain '$expectedMessageContains' $context, but was: ${error.message}",
            error.message?.contains(expectedMessageContains) == true
        )
    }
}
