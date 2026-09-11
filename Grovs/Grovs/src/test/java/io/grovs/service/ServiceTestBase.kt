package io.grovs.service

import io.grovs.e2e.E2ETestUtils
import io.grovs.handlers.GrovsContext
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.robolectric.RuntimeEnvironment

/** Real service and a fresh local HTTP server for transport-level tests. */
abstract class ServiceTestBase {
    protected lateinit var mockWebServer: MockWebServer
    protected lateinit var service: GrovsService

    @Before
    fun setUpService() {
        E2ETestUtils.setupMockUserAgent("Grovs SDK service tests")
        mockWebServer = E2ETestUtils.createMockWebServer()
        val grovsContext = GrovsContext().also {
            it.settings.baseURL = mockWebServer.url("/").toString()
        }
        service = GrovsService(RuntimeEnvironment.getApplication(), "test-key", grovsContext)
    }

    @After
    fun tearDownService() {
        mockWebServer.shutdown()
    }
}
