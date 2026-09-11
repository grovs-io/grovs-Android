package io.grovs.flows

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.grovs.handlers.CustomEventsManager
import io.grovs.handlers.EventsManager
import io.grovs.handlers.GrovsContext
import io.grovs.handlers.GrovsManager
import io.grovs.model.EventType
import io.grovs.model.events.PaymentEventType
import io.grovs.service.GrovsService
import io.grovs.storage.CustomEventsStorage
import io.grovs.storage.EventsStorage
import io.grovs.storage.LocalCache
import io.grovs.utils.InstantCompat
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Run both phases with tools/run-process-restart-test.py; the runner kills the app between them. */
@RunWith(AndroidJUnit4::class)
class ProcessRestartDeviceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val checkpoint get() = app.getSharedPreferences("process_restart_checkpoint", Context.MODE_PRIVATE)

    private class Rig(app: Application, server: MockWebServer) {
        val context = GrovsContext().also {
            it.grovsId = "process-test-device"
            it.settings.baseURL = server.url("/").toString()
        }
        val cache = LocalCache(app)
        val storage = EventsStorage(app)
        val customStorage = CustomEventsStorage(app)
        val service = GrovsService(app, "process-test-project", context)
        val events = EventsManager(app, context, "process-test-project", service, storage, cache).also {
            it.firstRequestTime = InstantCompat.now().minusMillis(20_000)
        }
        val custom = CustomEventsManager(app, context, service, customStorage, startFlushTimer = false)
        val manager = GrovsManager(app, app, context, "process-test-project", service, events,
            customEventsManager = custom, localCache = cache).also {
            // This fixture begins at the post-authentication boundary; storage and resolution are real.
            it.authenticationState = GrovsManager.AuthenticationState.AUTHENTICATED
        }
    }

    @Test
    fun seedOfflineQueue() = runBlocking {
        requirePhase("seed")
        app.getSharedPreferences(EventsStorage.GROVS_STORAGE, Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("grovs_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        checkpoint.edit().clear().commit()
        val attempts = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    attempts.add(request.path.orEmpty() to JSONObject(request.body.readUtf8().ifEmpty { "{}" }))
                    return MockResponse().setResponseCode(503).setBody("""{"error":"offline fixture"}""")
                }
            }
            start()
        }
        val rig = Rig(app, server)
        try {
            // Match the fresh-install authentication sequence: arm, persist launch events, resolve.
            rig.events.beginLinkResolution()
            rig.custom.setEventsHeld(true)
            rig.events.logAppLaunchEvents()
            rig.manager.track("offline_checkout", mapOf("order" to "persisted-order"), listOf("android"))
            rig.manager.logCustomPurchase(PaymentEventType.BUY, 199, "USD", "offline_sku", InstantCompat.now())
            rig.manager.handleIntent(Intent().setData(Uri.parse("https://demo.sqd.link/launch")), false)
            rig.custom.flush()

            val attemptedEvents = batchEvents(attempts)
            assertTrue("Lifecycle events must attempt delivery", attemptedEvents.any { it.has("event") })
            assertTrue("Custom events must attempt delivery", attemptedEvents.any {
                it.optString("event_name") == "offline_checkout"
            })
            assertTrue(attempts.any { it.first == "/api/v1/sdk/add_payment_event" })
            val lifecycle = rig.storage.getEvents().filter { it.event != EventType.TIME_SPENT }
            assertEquals(1, lifecycle.count { it.event == EventType.INSTALL })
            assertEquals(1, rig.customStorage.getEvents().size)
            assertEquals(1, rig.storage.getPaymentEvents().size)
            assertTrue(rig.cache.clipboardFlowPending)
            assertEquals(1, rig.cache.numberOfOpens)

            val custom = rig.customStorage.getEvents().single()
            val saved = JSONObject()
                .put("pid", Process.myPid())
                .put("session", rig.context.sessionId)
                .put("lifecycle_ids", JSONArray(lifecycle.map { it.eventId }))
                .put("custom_id", custom.eventId)
                .put("custom_created_at", custom.createdAt.toIsoString())
            // Wait for pending apply() writes on the SDK's actual preferences before killing it.
            assertTrue(app.getSharedPreferences(EventsStorage.GROVS_STORAGE, Context.MODE_PRIVATE)
                .edit().putBoolean("flow_test_disk_barrier", true).commit())
            assertTrue(checkpoint.edit().putString("seed", saved.toString()).commit())
            reportPhaseCompleted("seed")
        } finally {
            rig.manager.close()
            server.shutdown()
        }
    }

    @Test
    fun recoverQueueInNewProcess() = runBlocking {
        requirePhase("verify")
        val saved = JSONObject(checkpoint.getString("seed", null)
            ?: error("Run the seed phase first, without clearing app data between phases"))
        assertNotEquals("The runner must actually replace the Android process", saved.getInt("pid"), Process.myPid())
        val bodies = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    bodies.add(path to JSONObject(request.body.readUtf8().ifEmpty { "{}" }))
                    val body = when (path) {
                        "/api/v1/sdk/data_for_device_and_url" -> """{"link":null,"data":null}"""
                        "/api/v1/sdk/clipboard_status" -> """{"clipboard_active":false}"""
                        else -> "{}"
                    }
                    return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            start()
        }
        val rig = Rig(app, server)
        try {
            val oldSession = saved.getString("session")
            assertNotEquals(oldSession, rig.context.sessionId)
            assertTrue("Pending referral state must survive process death", rig.cache.clipboardFlowPending)
            assertEquals(1, rig.cache.numberOfOpens)
            assertEquals(saved.getString("custom_id"), rig.customStorage.getEvents().single().eventId)

            rig.events.logAppLaunchEvents()
            rig.manager.handleIntent(Intent().setData(Uri.parse("https://demo.sqd.link/reopen")), false)
            rig.custom.flush()
            val events = batchEvents(bodies)
            val oldIds = saved.getJSONArray("lifecycle_ids").let { array ->
                (0 until array.length()).map { array.getString(it) }.toSet()
            }
            val replayed = events.filter { it.optString("event_id") in oldIds }
            assertEquals(oldIds, replayed.map { it.getString("event_id") }.toSet())
            assertEquals("Each persisted lifecycle event is acknowledged once", oldIds.size, replayed.size)
            assertTrue(replayed.all { it.getString("session_id") == oldSession && !it.has("link") })
            assertEquals("Relaunch must not produce another INSTALL", 1, events.count { it.optString("event") == "install" })
            assertTrue(events.any { it.optString("event") == "app_open" && it.optString("session_id") == rig.context.sessionId })

            val custom = events.single { it.optString("event_name") == "offline_checkout" }
            assertEquals(saved.getString("custom_id"), custom.getString("event_id"))
            assertEquals(oldSession, custom.getString("session_id"))
            assertEquals(saved.getString("custom_created_at"), custom.getString("created_at"))
            assertEquals("persisted-order", custom.getJSONObject("properties").getString("order"))
            val purchase = bodies.single { it.first == "/api/v1/sdk/add_payment_event" }.second
            assertEquals(oldSession, purchase.getString("session_id"))
            assertEquals("offline_sku", purchase.getString("product_id"))
            assertTrue(rig.customStorage.getEvents().isEmpty())
            assertTrue(rig.storage.getPaymentEvents().isEmpty())
            assertFalse("Recovery must finish the pending clipboard decision", rig.cache.clipboardFlowPending)
            assertEquals(1, bodies.count { it.first == "/api/v1/sdk/clipboard_status" })
            assertEquals(2, rig.cache.numberOfOpens)
            reportPhaseCompleted("verify")
        } finally {
            rig.manager.close()
            server.shutdown()
        }
    }

    private fun batchEvents(requests: List<Pair<String, JSONObject>>): List<JSONObject> =
        requests.filter { it.first == "/api/v1/sdk/events/batch" }.flatMap { (_, body) ->
            val events = body.getJSONArray("events")
            (0 until events.length()).map(events::getJSONObject)
        }

    private fun reportPhaseCompleted(phase: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("grovs_process_phase_completed", phase)
            putInt("grovs_process_pid", Process.myPid())
        })
    }

    private fun requirePhase(expected: String) {
        val phase = InstrumentationRegistry.getArguments().getString("grovs.phase")
        assumeTrue("Use tools/run-process-restart-test.py for this two-process test", phase != null)
        assertEquals(expected, phase)
    }
}
