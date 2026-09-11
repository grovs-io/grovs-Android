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

    internal fun assertAuthenticated(manager: io.grovs.handlers.GrovsManager, context: String) {
        assertEquals(
            "Expected authenticationState to be AUTHENTICATED $context",
            io.grovs.handlers.GrovsManager.AuthenticationState.AUTHENTICATED,
            manager.authenticationState
        )
    }

    /**
     * Assert GrovsManager is in UNAUTHENTICATED state.
     */
    internal fun assertUnauthenticated(manager: io.grovs.handlers.GrovsManager, context: String) {
        val actual = manager.authenticationState
        assertEquals(
            "Expected authenticationState to be UNAUTHENTICATED $context, but was $actual",
            io.grovs.handlers.GrovsManager.AuthenticationState.UNAUTHENTICATED,
            actual
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
            coVerify { mockService.addEvents(match { l -> l.any { it.event == eventType } }) }
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
