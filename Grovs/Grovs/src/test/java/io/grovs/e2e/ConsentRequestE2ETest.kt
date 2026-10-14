package io.grovs.e2e

import io.grovs.handlers.GrovsContext
import io.grovs.handlers.RevocationReason
import io.grovs.service.ConsentBackend
import io.grovs.service.EndpointCase
import io.grovs.service.EndpointInventory
import io.grovs.service.GrovsService
import io.grovs.service.assertRevoked
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * E03: every row of the endpoint inventory over real HTTP with the real consent controller (its
 * production cleanup executor) and the real GrovsService. An enabled request succeeds; then a
 * request still waiting for its response when consent is withdrawn is cancelled for real, and the
 * response the server releases after consent is granted again is never published or retried.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [28])
class ConsentRequestE2ETest(private val case: EndpointCase) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = EndpointInventory.all.map { arrayOf<Any>(it) }
    }

    private lateinit var backend: ConsentBackend
    private lateinit var context: GrovsContext
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Before
    fun setUp() {
        E2ETestUtils.setupMockUserAgent("Grovs SDK consent E2E tests")
        backend = ConsentBackend()
        context = GrovsContext().also { it.settings.baseURL = backend.baseUrl }
    }

    @After
    fun tearDown() {
        scope.cancel()
        val cleanup = E2ETestUtils.retireConsentConfiguration(context)
        backend.close()
        assertNull(cleanup)
        assertEquals("requests to unrouted paths", emptyList<String>(), backend.unexpected.toList())
    }

    @Test
    fun `E03 an enabled request succeeds, and a response pending at revocation is never published`() = runBlocking {
        val service = GrovsService(context = RuntimeEnvironment.getApplication(), apiKey = "test-key", grovsContext = context)

        backend.phase = "enabled"
        backend.respond(case.path, case.successBody)
        case.checkSuccess(withTimeout(5_000) { case.invoke(service) })
        val positive = backend.seen.single()
        assertEquals(case.method, positive.method)
        case.checkRequest(positive.request, positive.body)

        val release = backend.hold(case.path, case.successBody)
        backend.phase = "in flight"
        val call = scope.async { runCatching { case.invoke(service) } }
        backend.awaitCount(case.path, 2)

        context.settings.sdkEnabled = false
        backend.phase = "revoked"
        // Transport cancellation is real: the call ends while the server still holds its response.
        assertRevoked(RevocationReason.REVOKED, withTimeout(5_000) { call.await() })
        val cleanup = context.consent.pendingWork()
        withTimeout(5_000) { cleanup.join() }
        assertEquals("nothing of the revoked request is still registered", 0, context.consent.registrationCount())

        context.settings.sdkEnabled = true
        backend.phase = "re-enabled"
        release.countDown()
        backend.assertQuiet(case.path, expected = 2, windowMs = 500)
        assertEquals("no retry under the new grant", 0, backend.count(case.path, "re-enabled"))
    }
}
