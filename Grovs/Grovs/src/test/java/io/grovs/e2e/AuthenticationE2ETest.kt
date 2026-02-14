package io.grovs.e2e

import android.app.Application
import io.grovs.Grovs
import io.grovs.TestAssertions.assertNotNullWithContext
import io.grovs.TestAssertions.assertTrueWithContext
import io.grovs.service.GrovsService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * E2E tests for SDK authentication flow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AuthenticationE2ETest {

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

    private suspend fun configureAndWaitForAuth(apiKey: String = "test-api-key") =
        E2ETestUtils.configureAndWaitForAuth(application, apiKey)

    // ==================== Authentication Flow Tests ====================

    @Test
    fun `test SDK authenticates device and makes requests on app startup`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer, grovsId = "grovs_e2e_authenticated_123")
            E2ETestUtils.enqueueDeviceResponse(mockWebServer, lastSeen = null)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)

            assertNotNullWithContext(
                GrovsService.testBaseUrl,
                "testBaseUrl",
                "before Grovs.configure()"
            )

            // Act
            val activityController = configureAndWaitForAuth("test-api-key-e2e")

            // Assert - verify requests and authentication state
            val requestCount = mockWebServer.requestCount
            assertTrue("SDK should make requests to MockWebServer", requestCount > 0)

            val firstRequest = mockWebServer.takeRequest(1, TimeUnit.SECONDS)
            assertNotNullWithContext(
                firstRequest,
                "first request",
                "after SDK configure and activity start"
            )

            // Verify SDK is actually authenticated
            E2ETestUtils.assertAuthenticationCompleted()

            // Verify grovsId if accessible (internal implementation detail)
            val storedGrovsId = E2ETestUtils.getGrovsId()
            if (storedGrovsId != null) {
                assertEquals(
                    "SDK should store mocked grovsId",
                    "grovs_e2e_authenticated_123",
                    storedGrovsId
                )
            }

            activityController?.stop()
            activityController?.destroy()
        }
    }

    @Test
    fun `test setup has MockWebServer running and testBaseUrl set`() {
        assertTrueWithContext(
            mockWebServer.url("/").toString().isNotEmpty(),
            "MockWebServer URL is not empty",
            "in test setup"
        )
        assertNotNullWithContext(
            GrovsService.testBaseUrl,
            "testBaseUrl",
            "in test setup"
        )
    }

    @Test
    fun `test direct HTTP call to MockWebServer works`() {
        // Arrange
        E2ETestUtils.enqueueAuthenticationResponse(mockWebServer, grovsId = "direct-test-123")

        // Act
        val client = okhttp3.OkHttpClient()
        val mediaType = "application/json".toMediaType()
        val requestBody = "{}".toRequestBody(mediaType)
        val request = okhttp3.Request.Builder()
            .url(mockWebServer.url("/authenticate"))
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()

        // Assert
        assertTrue("Response should be successful", response.isSuccessful)
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun `test SDK handles authentication with test environment`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)

            // Act
            configureAndWaitForAuth("my-api-key")

            // Assert
            val request = mockWebServer.takeRequest(1, TimeUnit.SECONDS)
            assertNotNull("Expected request to be made", request)

            val projectKey = request?.getHeader("PROJECT-KEY")
            assertNotNull("PROJECT-KEY header should be present", projectKey)
            assertTrue(
                "PROJECT-KEY should start with 'test_' when useTestEnvironment=true, got: $projectKey",
                projectKey?.startsWith("test_") == true
            )
        }
    }

    @Test
    fun `test SDK completes authentication flow successfully`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueAuthenticationResponse(mockWebServer)
            E2ETestUtils.enqueueDeviceResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)
            E2ETestUtils.enqueueEventResponse(mockWebServer)

            // Act
            val activityController = configureAndWaitForAuth()

            // Assert
            E2ETestUtils.assertAuthenticationCompleted()
            val requests = E2ETestUtils.collectAllRequests(mockWebServer)
            E2ETestUtils.assertAuthenticateRequestValues(requests)
            E2ETestUtils.assertDeviceRequestValues(requests)

            activityController?.stop()
            activityController?.destroy()
        }
    }

    @Test
    fun `test SDK handles authentication timeout gracefully`() {
        runBlocking {
            // Arrange
            E2ETestUtils.enqueueDelayedResponse(mockWebServer, 30_000)

            // Act
            Grovs.configure(application, "test-api-key", useTestEnvironment = true)

            E2ETestUtils.processMainLooper()

            val activityController = Robolectric.buildActivity(TestActivity::class.java)
            activityController.create()
            E2ETestUtils.processMainLooper()
            activityController.start()
            E2ETestUtils.processMainLooper()

            delay(1000)
            E2ETestUtils.processMainLooper()

            // Assert
            assertTrue(
                "SDK should attempt authentication even with slow server",
                mockWebServer.requestCount >= 1
            )
            E2ETestUtils.assertAuthenticationInProgress()
            E2ETestUtils.assertSdkFunctionalAfterError()

            activityController.stop()
            activityController.destroy()
        }
    }
}
