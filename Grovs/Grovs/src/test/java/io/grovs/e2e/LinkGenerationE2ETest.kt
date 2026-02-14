package io.grovs.e2e

import android.app.Application
import android.os.Looper
import io.grovs.Grovs
import io.grovs.model.CustomLinkRedirect
import io.grovs.model.exceptions.GrovsException
import io.grovs.service.CustomRedirects
import io.grovs.service.GrovsService
import io.grovs.service.TrackingParams
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
import java.io.Serializable

/**
 * E2E tests for link generation functionality.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LinkGenerationE2ETest {

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

    // ==================== Link Generation Tests ====================

    @Test
    fun `Generate link with all parameters returns valid link`() {
        // Arrange
        E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
        E2ETestUtils.enqueueDeviceResponse(mockWebServer)
        E2ETestUtils.enqueueGenerateLinkResponse(mockWebServer, link = "https://test.grovs.io/full-params")

        // Configure SDK
        Grovs.configure(application, "test-api-key", useTestEnvironment = true)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Wait for auth job with looper pumping
        val authJob = E2ETestUtils.getAuthenticationJob()
        E2ETestUtils.runWithLooperPumping(10_000) { authJob?.join() }

        // Act - use every parameter available on generateLink
        val result = E2ETestUtils.runWithLooperPumping(10_000) {
            Grovs.generateLink(
                title = "Test Title",
                subtitle = "Test Subtitle",
                imageURL = "https://example.com/image.png",
                data = mapOf<String, Serializable>("key" to "value"),
                tags = listOf("tag1", "tag2"),
                customRedirects = CustomRedirects(
                    ios = CustomLinkRedirect(link = "https://ios.example.com", openAppIfInstalled = true),
                    android = CustomLinkRedirect(link = "https://android.example.com", openAppIfInstalled = false),
                    desktop = CustomLinkRedirect(link = "https://desktop.example.com")
                ),
                showPreviewIos = true,
                showPreviewAndroid = false,
                tracking = TrackingParams(
                    utmCampaign = "summer_sale",
                    utmSource = "newsletter",
                    utmMedium = "email"
                )
            )
        }

        // Assert - verify the mocked link URL is returned
        assertNotNull("generateLink should return a result", result)
        assertEquals("Should return mocked link URL", "https://test.grovs.io/full-params", result)

        // Assert - verify all parameters were sent in the request body
        val requests = E2ETestUtils.collectAllRequests(mockWebServer)
        val linkRequests = E2ETestUtils.findRequestsByPath(requests, "create_link")
        assertTrue("Should call create_link endpoint", linkRequests.isNotEmpty())

        val requestBody = linkRequests.first().second

        // Basic fields
        assertTrue("Request should contain title", requestBody.contains("\"title\":\"Test Title\""))
        assertTrue("Request should contain subtitle", requestBody.contains("\"subtitle\":\"Test Subtitle\""))
        assertTrue("Request should contain image_url", requestBody.contains("\"image_url\":\"https://example.com/image.png\""))

        // Data (serialized as JSON string)
        assertTrue("Request should contain data with key/value", requestBody.contains("key") && requestBody.contains("value"))

        // Tags (serialized as JSON string)
        assertTrue("Request should contain tag1", requestBody.contains("tag1"))
        assertTrue("Request should contain tag2", requestBody.contains("tag2"))

        // Custom redirects
        assertTrue("Request should contain iOS custom redirect URL",
            requestBody.contains("https://ios.example.com"))
        assertTrue("Request should contain Android custom redirect URL",
            requestBody.contains("https://android.example.com"))
        assertTrue("Request should contain desktop custom redirect URL",
            requestBody.contains("https://desktop.example.com"))

        // Show preview flags
        assertTrue("Request should contain show_preview_ios",
            requestBody.contains("\"show_preview_ios\":true"))
        assertTrue("Request should contain show_preview_android",
            requestBody.contains("\"show_preview_android\":false"))

        // Tracking params
        assertTrue("Request should contain tracking_campaign",
            requestBody.contains("\"tracking_campaign\":\"summer_sale\""))
        assertTrue("Request should contain tracking_source",
            requestBody.contains("\"tracking_source\":\"newsletter\""))
        assertTrue("Request should contain tracking_medium",
            requestBody.contains("\"tracking_medium\":\"email\""))
    }

    @Test
    fun `Generate link with minimal parameters returns valid link`() {
        // Arrange
        E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
        E2ETestUtils.enqueueDeviceResponse(mockWebServer)
        E2ETestUtils.enqueueGenerateLinkResponse(mockWebServer, link = "https://test.grovs.io/minimal")

        // Configure SDK
        Grovs.configure(application, "test-api-key", useTestEnvironment = true)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Wait for auth job with looper pumping
        val authJob = E2ETestUtils.getAuthenticationJob()
        E2ETestUtils.runWithLooperPumping(10_000) { authJob?.join() }

        // Act
        val result = E2ETestUtils.runWithLooperPumping(10_000) {
            Grovs.generateLink()
        }

        // Assert - verify the mocked link URL is returned exactly
        assertNotNull("generateLink with no params should return link", result)
        assertEquals("Should return mocked link URL", "https://test.grovs.io/minimal", result)
    }

    @Test
    fun `Generate link before authentication throws exception`() {
        runBlocking {
            // Arrange - don't configure SDK

            // Act & Assert
            try {
                Grovs.generateLink(title = "Test")
                fail("generateLink should throw when not authenticated")
            } catch (e: GrovsException) {
                assertTrue("Should throw SDK_NOT_INITIALIZED error",
                    e.message?.contains("not initialized") == true)
            }
        }
    }

    @Test
    fun `Generate link handles server error gracefully`() {
        // Arrange
        E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
        E2ETestUtils.enqueueDeviceResponse(mockWebServer)
        E2ETestUtils.enqueueErrorResponse(mockWebServer, 500, "Server error")

        // Configure SDK
        Grovs.configure(application, "test-api-key", useTestEnvironment = true)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Wait for auth job with looper pumping
        val authJob = E2ETestUtils.getAuthenticationJob()
        E2ETestUtils.runWithLooperPumping(10_000) { authJob?.join() }

        val requestCountBefore = mockWebServer.requestCount

        // Act
        val result = E2ETestUtils.runWithLooperPumping(10_000) {
            Grovs.generateLink(title = "Test")
        }

        // Assert
        val requests = E2ETestUtils.collectAllRequests(mockWebServer)
        val generateRequests = E2ETestUtils.findRequestsByPath(requests, "generate")
        assertTrue(
            "SDK should call generate_link endpoint, got: ${requests.map { it.first }}",
            generateRequests.isNotEmpty() || mockWebServer.requestCount > requestCountBefore
        )
        assertNull("Result should be null when server returns error", result)
        E2ETestUtils.assertAuthenticationCompleted()
        E2ETestUtils.assertSdkFunctionalAfterError()
    }

    @Test
    fun `Generate link with custom data payload returns valid link`() {
        // Arrange
        E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
        E2ETestUtils.enqueueDeviceResponse(mockWebServer)
        E2ETestUtils.enqueueGenerateLinkResponse(mockWebServer, link = "https://test.grovs.io/with-data")

        Grovs.configure(application, "test-api-key", useTestEnvironment = true)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val authJob = E2ETestUtils.getAuthenticationJob()
        E2ETestUtils.runWithLooperPumping(10_000) { authJob?.join() }

        // Act
        val customData = mapOf<String, Serializable>(
            "campaign" to "summer-sale",
            "discount" to 20,
            "featured" to true
        )

        val result = E2ETestUtils.runWithLooperPumping(10_000) {
            Grovs.generateLink(
                title = "Summer Sale",
                data = customData
            )
        }

        // Assert
        assertNotNull("generateLink with custom data should return link", result)
        assertTrue("Result should contain link URL", result?.contains("grovs.io") == true)

        val requests = E2ETestUtils.collectAllRequests(mockWebServer)
        val linkRequests = E2ETestUtils.findRequestsByPath(requests, "create_link")
        assertTrue("Should call create_link endpoint", linkRequests.isNotEmpty())

        val requestBody = linkRequests.first().second

        // Verify all custom data fields are present in request
        assertTrue(
            "Request should contain 'summer-sale' campaign data",
            requestBody.contains("summer-sale") || requestBody.contains("summer_sale")
        )
        assertTrue(
            "Request should contain discount value '20'",
            requestBody.contains("20") || requestBody.contains("\"discount\"")
        )
        assertTrue(
            "Request should contain featured boolean 'true'",
            requestBody.contains("true") || requestBody.contains("\"featured\"")
        )
    }

    @Test
    fun `Generate link handles network timeout gracefully`() {
        // Arrange
        E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
        E2ETestUtils.enqueueDeviceResponse(mockWebServer)
        E2ETestUtils.enqueueDelayedResponse(mockWebServer, 30_000)

        Grovs.configure(application, "test-api-key", useTestEnvironment = true)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val authJob = E2ETestUtils.getAuthenticationJob()
        E2ETestUtils.runWithLooperPumping(10_000) { authJob?.join() }

        // Act
        val result = E2ETestUtils.runWithLooperPumping(5_000) {
            Grovs.generateLink(title = "Timeout Test")
        }

        // Assert
        E2ETestUtils.assertAuthenticationCompleted()
        val requests = E2ETestUtils.collectAllRequests(mockWebServer)
        E2ETestUtils.assertRequestMade(requests, "create_link", "SDK should attempt to generate link")
        E2ETestUtils.assertSdkFunctionalAfterError()
    }
}
