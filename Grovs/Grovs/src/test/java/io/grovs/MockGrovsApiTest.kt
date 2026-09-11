package io.grovs

import io.grovs.model.AppDetails
import io.grovs.model.AuthenticationResponse
import io.grovs.model.BatchEventsRequest
import io.grovs.model.BatchEventsResponse
import io.grovs.model.GenerateLinkResponse
import io.grovs.model.GetDeviceResponse
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import retrofit2.Response

/** Contracts for the configurable fake used by service tests. SDK workflows live in e2e/. */
class MockGrovsApiTest {
    private val api = MockGrovsApi()
    private val appDetails = AppDetails(
        version = "1.0.0", build = "1", bundle = "io.grovs.test",
        device = "TestDevice", deviceID = "test_device_id_123", userAgent = "TestUserAgent/1.0",
    )

    @Test
    fun `authenticate returns the configured identity`() = runTest {
        api.authenticateResponse = Response.success(
            AuthenticationResponse("grovs_test_123", "testapp", null, null)
        )

        val result = api.authenticate(appDetails)

        assertTrue(result.isSuccessful)
        assertEquals("grovs_test_123", result.body()?.grovsId)
        assertEquals("testapp", result.body()?.uriScheme)
    }

    @Test
    fun `authenticate returns the configured 401`() = runTest {
        api.authenticateResponse = MockGrovsApi.createErrorResponseTyped(401, "Invalid API key")

        val result = api.authenticate(appDetails)

        assertFalse(result.isSuccessful)
        assertEquals(401, result.code())
    }

    @Test
    fun `generateLink returns the configured link and records the call`() = runTest {
        val link = "https://app.grovs.io/abc123"
        api.generateLinkResponse = Response.success(GenerateLinkResponse(link))

        val result = api.generateLink(TestFixtures.createGenerateLinkRequest("Test Title", "Test Subtitle"))

        assertTrue(result.isSuccessful)
        assertEquals(link, result.body()?.link)
        assertTrue(api.verifyGenerateLinkCalled())
    }

    @Test
    fun `generateLink returns the configured 429`() = runTest {
        api.generateLinkResponse = MockGrovsApi.createErrorResponseTyped(429, "Rate limit exceeded")

        val result = api.generateLink(TestFixtures.createGenerateLinkRequest())

        assertFalse(result.isSuccessful)
        assertEquals(429, result.code())
    }

    @Test
    fun `payloadFor returns configured link data and tracking`() = runTest {
        val details = TestFixtures.createDeeplinkDetails(
            data = mapOf("promo_code" to "SAVE20" as Object, "discount" to 20 as Object),
            tracking = mapOf("utm_source" to "facebook" as Object, "utm_campaign" to "summer" as Object),
        )
        api.payloadResponse = Response.success(details)

        val result = api.payloadFor(appDetails)

        assertTrue(result.isSuccessful)
        assertEquals(details.link, result.body()?.link)
        assertEquals(details.data, result.body()?.data)
        assertEquals(details.tracking, result.body()?.tracking)
    }

    @Test
    fun `payloadWithLinkFor returns configured link and payload values`() = runTest {
        val details = TestFixtures.createDeeplinkDetails(
            link = "https://app.grovs.io/specific",
            data = mapOf("product_id" to "SKU123" as Object, "promo" to "SUMMER50" as Object),
        )
        api.payloadWithLinkResponse = Response.success(details)

        val result = api.payloadWithLinkFor(appDetails)

        assertTrue(result.isSuccessful)
        assertEquals(details.link, result.body()?.link)
        assertEquals(details.data, result.body()?.data)
    }

    @Test
    fun `batch events return success and record the call`() = runTest {
        api.addEventsBatchResponse = Response.success(BatchEventsResponse(accepted = 1, rejected = 0))

        val result = api.addEventsBatch(BatchEventsRequest(listOf(TestFixtures.createEvent())))

        assertTrue(result.isSuccessful)
        assertTrue(api.verifyAddEventCalled())
    }

    @Test
    fun `device check returns the configured response`() = runTest {
        api.getDeviceResponse = Response.success(GetDeviceResponse(lastSeen = null))

        val result = api.getDeviceFor("new_device")

        assertTrue(result.isSuccessful)
        assertNotNull(result.body())
        assertNull(result.body()?.lastSeen)
    }
}
