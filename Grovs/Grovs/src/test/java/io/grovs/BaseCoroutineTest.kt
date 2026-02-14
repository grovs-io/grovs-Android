package io.grovs

import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * Base test class for tests that require coroutine support.
 * 
 * Provides:
 * - StandardTestDispatcher for coroutine testing
 * - Automatic Dispatchers.Main setup/teardown
 * - DebugLogger initialization
 * - MockK cleanup
 * 
 * Usage:
 * ```kotlin
 * class MyTest : BaseCoroutineTest() {
 *     @BeforeEach
 *     override fun setUp() {
 *         super.setUp()
 *         // Additional setup
 *     }
 * }
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class BaseCoroutineTest {

    protected val testDispatcher: TestDispatcher = StandardTestDispatcher()

    @BeforeEach
    open fun setUp() {
        Dispatchers.setMain(testDispatcher)
        DebugLogger.instance.logLevel = LogLevel.INFO
    }

    @AfterEach
    open fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }
}
