package io.grovs.e2e

import android.os.Looper
import androidx.lifecycle.viewModelScope
import io.grovs.handlers.GrovsContext
import io.grovs.handlers.RevocationReason
import io.grovs.model.notifications.Notification
import io.grovs.service.ConsentBackend
import io.grovs.service.EndpointCase
import io.grovs.service.EndpointInventory
import io.grovs.service.GrovsService
import io.grovs.service.assertRevoked
import io.grovs.viewmodels.AutoDisplayedNotificationViewModel
import io.grovs.viewmodels.NotificationsMainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

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

/** E03 for the calls the notification view models make directly on the service (no inherited operation). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConsentViewModelRequestE2ETest {

    private lateinit var backend: ConsentBackend
    private lateinit var context: GrovsContext
    private lateinit var service: GrovsService

    private val notificationsPath = "notifications_for_device"
    private val markPath = "mark_notification_as_read"

    @Before
    fun setUp() {
        E2ETestUtils.setupMockUserAgent("Grovs SDK consent E2E tests")
        backend = ConsentBackend().apply { phase = "enabled" }
        context = GrovsContext().also { it.settings.baseURL = backend.baseUrl }
        service = GrovsService(context = RuntimeEnvironment.getApplication(), apiKey = "test-key", grovsContext = context)
    }

    @After
    fun tearDown() {
        val cleanup = E2ETestUtils.retireConsentConfiguration(context)
        shadowOf(Looper.getMainLooper()).idle()
        backend.close()
        assertNull(cleanup)
        assertEquals("requests to unrouted paths", emptyList<String>(), backend.unexpected.toList())
    }

    private fun page(vararg ids: Int) = ids.joinToString(",", """{"notifications":[""", "]}") { id ->
        """{"id":$id,"title":"n$id","updated_at":"2024-01-01T10:00:00.000Z","subtitle":null,"auto_display":false,"access_url":null,"read":false}"""
    }

    private fun pageRequested(index: Int) = JSONObject(backend.seen.filter { it.path == notificationsPath }[index].body).getInt("page")

    private fun Job.noActiveChildren() = children.none { it.isActive }

    /** Pumps the main looper for [windowMs], asserting [check] throughout. */
    private fun observe(windowMs: Long = 500, check: () -> Unit) {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(windowMs)
        while (System.nanoTime() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            check()
            Thread.sleep(10)
        }
        check()
    }

    @Test
    fun `E03 NotificationsMainViewModel page load - positive, then revoked while pending`() {
        val viewModel = NotificationsMainViewModel(RuntimeEnvironment.getApplication()).also { it.grovsService = service }
        backend.respond(notificationsPath, page(7))
        viewModel.loadMoreNotifications()
        E2ETestUtils.waitForCondition(description = "page 1") { viewModel.notifications.value.size == 1 && !viewModel.isLoading.value }
        assertEquals(1, pageRequested(0))

        val release = backend.hold(notificationsPath, page(8))
        viewModel.loadMoreNotifications()
        backend.awaitCount(notificationsPath, 2)
        assertEquals(2, pageRequested(1))
        context.settings.sdkEnabled = false
        E2ETestUtils.waitForCondition(description = "the revoked load to end") { !viewModel.isLoading.value }

        context.settings.sdkEnabled = true
        backend.phase = "re-enabled"
        release.countDown()
        observe {
            assertEquals("a revoked page is never appended", listOf(7), viewModel.notifications.value.map { it.id })
            assertEquals("and never retried", 2, backend.count(notificationsPath))
        }

        // Positive control after the new grant: an explicit load asks for page 2 again.
        backend.respond(notificationsPath, page(8))
        viewModel.loadMoreNotifications()
        E2ETestUtils.waitForCondition(description = "page 2 after re-enable") { viewModel.notifications.value.size == 2 }
        assertEquals(2, pageRequested(2))
    }

    @Test
    fun `E03 NotificationsMainViewModel markAsRead - positive, then revoked while pending`() {
        val viewModel = NotificationsMainViewModel(RuntimeEnvironment.getApplication()).also { it.grovsService = service }
        backend.respond(notificationsPath, page(7, 8))
        viewModel.loadMoreNotifications()
        E2ETestUtils.waitForCondition(description = "the page") { viewModel.notifications.value.size == 2 }
        val (first, second) = viewModel.notifications.value

        backend.respond(markPath, "{}")
        viewModel.markAsRead(first)
        E2ETestUtils.waitForCondition(description = "positive markAsRead") { first.read }
        assertEquals(7, JSONObject(backend.seen.last().body).getInt("id"))

        val release = backend.hold(markPath, "{}")
        viewModel.markAsRead(second)
        backend.awaitCount(markPath, 2)
        context.settings.sdkEnabled = false
        val scopeJob = viewModel.viewModelScope.coroutineContext[Job]!!
        E2ETestUtils.waitForCondition(description = "the revoked markAsRead to end") { scopeJob.noActiveChildren() }
        assertTrue("the view model's scope itself is not cancelled", scopeJob.isActive)

        context.settings.sdkEnabled = true
        backend.phase = "re-enabled"
        release.countDown()
        observe {
            assertFalse("a revoked acknowledgement never marks the notification read", second.read)
            assertEquals(2, backend.count(markPath))
        }
    }

    @Test
    fun `E03 AutoDisplayedNotificationViewModel markAsRead - positive, then revoked while pending`() {
        val viewModel = AutoDisplayedNotificationViewModel(RuntimeEnvironment.getApplication()).also { it.grovsService = service }
        val notification = Notification(9, "auto", io.grovs.utils.InstantCompat.now(), null, true, null, false)
        val scopeJob = viewModel.viewModelScope.coroutineContext[Job]!!

        backend.respond(markPath, "{}")
        viewModel.markAsRead(notification)
        E2ETestUtils.waitForCondition(description = "positive markAsRead") {
            backend.count(markPath) == 1 && scopeJob.noActiveChildren()
        }
        assertEquals(9, JSONObject(backend.seen.single().body).getInt("id"))

        val release = backend.hold(markPath, "{}")
        viewModel.markAsRead(notification)
        backend.awaitCount(markPath, 2)
        context.settings.sdkEnabled = false
        E2ETestUtils.waitForCondition(description = "the revoked markAsRead to end before its response") { scopeJob.noActiveChildren() }

        context.settings.sdkEnabled = true
        backend.phase = "re-enabled"
        release.countDown()
        observe { assertEquals("never retried", 2, backend.count(markPath)) }
    }
}
