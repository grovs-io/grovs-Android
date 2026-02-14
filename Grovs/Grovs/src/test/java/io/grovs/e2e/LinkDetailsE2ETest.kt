package io.grovs.e2e

import android.app.Application
import android.os.Looper
import io.grovs.Grovs
import io.grovs.model.exceptions.GrovsException
import io.grovs.service.GrovsService
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * E2E tests for link details functionality.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LinkDetailsE2ETest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var application: Application

    @get:Rule
    val testName = TestName()

    @Before
    fun setUp() {
        println("\n========== Running: ${testName.methodName} ==========")
        E2ETestUtils.resetGrovsSingleton()
        E2ETestUtils.setupMockGlInfo()
        mockWebServer = E2ETestUtils.createMockWebServer()
        application = RuntimeEnvironment.getApplication()
        E2ETestUtils.setupTestApplication(application)
    }

    @After
    fun tearDown() {
        E2ETestUtils.cleanupMockWebServer(mockWebServer)
    }

    // ==================== Link Details Tests ====================

    @Test
    fun `Link details before authentication throws exception`() {
        runBlocking {
            // Arrange - don't configure SDK

            // Act & Assert
            try {
                Grovs.linkDetails(path = "test-path")
                fail("linkDetails should throw when not authenticated")
            } catch (e: GrovsException) {
                assertTrue("Should throw SDK_NOT_INITIALIZED error",
                    e.message?.contains("not initialized") == true)
            }
        }
    }

    @Test
    fun `Link details returns valid details for existing link`() {
        // Arrange
        E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
        E2ETestUtils.enqueueDeviceResponse(mockWebServer)
        E2ETestUtils.enqueueLinkDetailsResponse(mockWebServer, title = "Test Link", subtitle = "Description")

        // Configure SDK
        Grovs.configure(application, "test-api-key", useTestEnvironment = true)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Wait for auth job with looper pumping
        val authJob = E2ETestUtils.getAuthenticationJob()
        E2ETestUtils.runWithLooperPumping(10_000) { authJob?.join() }

        // Act
        val result = E2ETestUtils.runWithLooperPumping(10_000) {
            Grovs.linkDetails(path = "test-link-path")
        }

        // Assert - verify the mocked title and subtitle are returned
        assertNotNull("linkDetails should return result", result)
        assertEquals("Should return mocked title", "Test Link", result?.get("title"))
        assertEquals("Should return mocked subtitle", "Description", result?.get("subtitle"))
    }

    @Test
    fun `Link details with custom data returns data map`() {
        // Arrange
        E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
        E2ETestUtils.enqueueDeviceResponse(mockWebServer)
        E2ETestUtils.enqueueLinkDetailsResponse(mockWebServer, title = "Product Page", subtitle = "Check out this item")

        Grovs.configure(application, "test-api-key", useTestEnvironment = true)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val authJob = E2ETestUtils.getAuthenticationJob()
        E2ETestUtils.runWithLooperPumping(10_000) { authJob?.join() }

        // Act
        val result = E2ETestUtils.runWithLooperPumping(10_000) {
            Grovs.linkDetails(path = "product-page")
        }

        // Assert - verify the mocked title and subtitle are returned exactly
        assertNotNull("linkDetails should return result with custom data", result)
        assertEquals("Should return mocked title", "Product Page", result?.get("title"))
        assertEquals("Should return mocked subtitle", "Check out this item", result?.get("subtitle"))
    }

    @Test
    fun `Link details for non-existent link returns null or error`() {
        // Arrange
        E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
        E2ETestUtils.enqueueDeviceResponse(mockWebServer)
        E2ETestUtils.enqueueErrorResponse(mockWebServer, 404, "Link not found")

        Grovs.configure(application, "test-api-key", useTestEnvironment = true)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val authJob = E2ETestUtils.getAuthenticationJob()
        E2ETestUtils.runWithLooperPumping(10_000) { authJob?.join() }

        // Act
        val result = E2ETestUtils.runWithLooperPumping(10_000) {
            Grovs.linkDetails(path = "non-existent-path")
        }

        // Assert
        assertNull("linkDetails for non-existent path should return null", result)
    }

    @Test
    fun `Link details handles server error gracefully`() {
        // Arrange
        E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
        E2ETestUtils.enqueueDeviceResponse(mockWebServer)
        E2ETestUtils.enqueueErrorResponse(mockWebServer, 500, "Internal Server Error")

        Grovs.configure(application, "test-api-key", useTestEnvironment = true)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val authJob = E2ETestUtils.getAuthenticationJob()
        E2ETestUtils.runWithLooperPumping(10_000) { authJob?.join() }

        // Act
        val result = E2ETestUtils.runWithLooperPumping(10_000) {
            Grovs.linkDetails(path = "error-test")
        }

        // Assert
        assertNull("linkDetails should return null on server error", result)
        E2ETestUtils.assertAuthenticationCompleted()
        val requests = E2ETestUtils.collectAllRequests(mockWebServer)
        E2ETestUtils.assertRequestMade(requests, "link_details", "SDK should attempt to fetch link details")
        E2ETestUtils.assertSdkFunctionalAfterError()
    }
}
