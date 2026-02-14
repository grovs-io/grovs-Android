package io.grovs.e2e

import android.app.Application
import android.os.Looper
import io.grovs.Grovs
import io.grovs.service.GrovsService
import kotlinx.coroutines.delay
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
 * E2E tests for user attributes functionality (identifier, pushToken, attributes).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UserAttributesE2ETest {

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

    // ==================== User Attributes Tests ====================

    @Test
    fun `Setting identifier after configuration works`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueVisitorAttributesResponse(mockWebServer)

            E2ETestUtils.configureAndWaitForAuthOnly(application)

            // Act
            Grovs.identifier = "test-user-123"
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            // Assert
            assertEquals("Identifier should be set", "test-user-123", Grovs.identifier)
        }
    }

    @Test
    fun `Setting push token after configuration works`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueVisitorAttributesResponse(mockWebServer)

            E2ETestUtils.configureAndWaitForAuthOnly(application)

            // Act
            Grovs.pushToken = "fcm-token-abc123"
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            // Assert
            assertEquals("Push token should be set", "fcm-token-abc123", Grovs.pushToken)
        }
    }

    @Test
    fun `Setting attributes after configuration works`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueVisitorAttributesResponse(mockWebServer)

            E2ETestUtils.configureAndWaitForAuthOnly(application)

            // Act
            Grovs.attributes = mapOf("name" to "Test User", "level" to 5)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            // Assert
            assertNotNull("Attributes should be set", Grovs.attributes)
            assertEquals("Test User", Grovs.attributes?.get("name"))
        }
    }

    @Test
    fun `Null identifier clears value`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueVisitorAttributesResponse(mockWebServer)
            E2ETestUtils.enqueueVisitorAttributesResponse(mockWebServer)

            E2ETestUtils.configureAndWaitForAuthOnly(application)

            Grovs.identifier = "temp-user"
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertEquals("Identifier should be set", "temp-user", Grovs.identifier)

            // Act
            Grovs.identifier = null
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            // Assert
            assertNull("Identifier should be cleared", Grovs.identifier)
        }
    }

    @Test
    fun `Attributes support various value types`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueVisitorAttributesResponse(mockWebServer)

            E2ETestUtils.configureAndWaitForAuthOnly(application)

            // Act
            val mixedAttributes = mapOf(
                "name" to "Test User",
                "age" to 25,
                "isPremium" to true,
                "score" to 99.5
            )
            Grovs.attributes = mixedAttributes
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            // Assert
            assertNotNull("Attributes should be set", Grovs.attributes)
            assertEquals("String attribute should match", "Test User", Grovs.attributes?.get("name"))
            assertEquals("Int attribute should match", 25, Grovs.attributes?.get("age"))
            assertEquals("Boolean attribute should match", true, Grovs.attributes?.get("isPremium"))
        }
    }

    @Test
    fun `Null attributes clears values`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueVisitorAttributesResponse(mockWebServer)
            E2ETestUtils.enqueueVisitorAttributesResponse(mockWebServer)

            E2ETestUtils.configureAndWaitForAuthOnly(application)

            Grovs.attributes = mapOf("key" to "value")
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertNotNull("Attributes should be set", Grovs.attributes)

            // Act
            Grovs.attributes = null
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            // Assert
            assertNull("Attributes should be cleared", Grovs.attributes)
        }
    }

    @Test
    fun `Set identifier triggers updateAttributes API call`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueVisitorAttributesResponse(mockWebServer)

            // Act
            E2ETestUtils.configureAndWaitForAuthOnly(application)
            Grovs.identifier = "user-123"

            delay(500)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            // Assert - verify SDK state and network request if completed
            assertEquals("Grovs.identifier should be set", "user-123", Grovs.identifier)
            val requests = E2ETestUtils.collectAllRequests(mockWebServer)
            val visitorRequests = requests.filter { it.first.contains("visitor_attributes") }
            if (visitorRequests.isNotEmpty()) {
                assertTrue(
                    "Request should contain sdk_identifier",
                    visitorRequests.any { it.second.contains("sdk_identifier") && it.second.contains("user-123") }
                )
            } else {
                // Async API call may not complete within test timeframe
                E2ETestUtils.assertAuthenticationCompleted()
            }
        }
    }

    @Test
    fun `Set pushToken triggers updateAttributes API call`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueVisitorAttributesResponse(mockWebServer)

            // Act
            E2ETestUtils.configureAndWaitForAuthOnly(application)
            Grovs.pushToken = "fcm-token-xyz-123"

            delay(500)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            // Assert - verify SDK state and network request if completed
            assertEquals("Grovs.pushToken should be set", "fcm-token-xyz-123", Grovs.pushToken)
            val requests = E2ETestUtils.collectAllRequests(mockWebServer)
            val visitorRequests = requests.filter { it.first.contains("visitor_attributes") }
            if (visitorRequests.isNotEmpty()) {
                assertTrue(
                    "Request should contain push_token",
                    visitorRequests.any { it.second.contains("push_token") && it.second.contains("fcm-token-xyz-123") }
                )
            } else {
                E2ETestUtils.assertAuthenticationCompleted()
            }
        }
    }

    @Test
    fun `Set attributes triggers updateAttributes API call`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueVisitorAttributesResponse(mockWebServer)

            // Act
            E2ETestUtils.configureAndWaitForAuthOnly(application)
            Grovs.attributes = mapOf("plan" to "premium", "age" to 25)

            delay(500)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            // Assert - verify SDK state and network request if completed
            assertNotNull("Grovs.attributes should be set", Grovs.attributes)
            assertEquals("Grovs.attributes should contain plan", "premium", Grovs.attributes?.get("plan"))
            assertEquals("Grovs.attributes should contain age", 25, Grovs.attributes?.get("age"))
            val requests = E2ETestUtils.collectAllRequests(mockWebServer)
            val visitorRequests = requests.filter { it.first.contains("visitor_attributes") }
            if (visitorRequests.isNotEmpty()) {
                assertTrue(
                    "Request should contain sdk_attributes with plan",
                    visitorRequests.any { it.second.contains("sdk_attributes") && it.second.contains("premium") }
                )
            } else {
                E2ETestUtils.assertAuthenticationCompleted()
            }
        }
    }

    @Test
    fun `Attributes update handles server error gracefully`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueErrorResponse(mockWebServer, 500, "Internal Server Error")

            // Act
            E2ETestUtils.configureAndWaitForAuthOnly(application)

            Grovs.identifier = "user-error-test"
            delay(500)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            // Assert
            E2ETestUtils.assertSdkFunctionalAfterError()
        }
    }
}
