package io.grovs.handlers

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.GsonBuilder
import io.grovs.model.BatchEventsRequest
import io.grovs.model.CustomEvent
import io.grovs.service.IGrovsService
import io.grovs.storage.CustomEventsStorage
import io.grovs.storage.EventsStorage
import io.grovs.storage.ICustomEventsStorage
import io.grovs.utils.LSJsonInstantCompatTypeAdapterFactory
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [28])
class CustomEventsManagerInAppTest {

    private lateinit var context: Context
    private lateinit var grovsContext: GrovsContext
    private lateinit var storage: ICustomEventsStorage
    private lateinit var manager: CustomEventsManager
    private val stored = mutableListOf<CustomEvent>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        grovsContext = GrovsContext()
        grovsContext.markAuthenticated("test-grovs-id", grovsContext.consent.currentConfiguration)
        storage = mockk(relaxed = true)
        stored.clear()
        coEvery { storage.addEvent(any()) } answers { stored.add(firstArg()); Unit }
        manager = CustomEventsManager(
            context = context, grovsContext = grovsContext, grovsService = mockk<IGrovsService>(relaxed = true),
            customEventsStorage = storage, startFlushTimer = false,
        )
    }

    @After
    fun tearDown() {
        manager.close()
    }

    @Test
    fun `an in-app event keeps its id, its reserved name and the session`() = runTest {
        manager.trackInApp("view-1", "in_app_viewed", mapOf("campaign_id" to "c1", "revision" to 1L, "template" to "modal"))

        val event = stored.single()
        assertEquals("view-1", event.eventId)
        assertEquals("in_app_viewed", event.eventName)
        assertEquals(grovsContext.sessionId, event.sessionId)
        assertEquals(mapOf("campaign_id" to "c1", "revision" to 1L, "template" to "modal"), event.properties)
    }

    @Test
    fun `an in-app event takes the link of the session it belongs to`() = runTest {
        manager.setLinkForFutureEvents("https://go.example/abc", grovsContext.sessionId)
        manager.trackInApp("e1", "in_app_clicked", mapOf("campaign_id" to "c1"))
        assertEquals("https://go.example/abc", stored.single().link)
    }

    @Test
    fun `nothing is stored once consent is withdrawn`() = runTest {
        grovsContext.consent.revoke()
        manager.trackInApp("e1", "in_app_dismissed", mapOf("campaign_id" to "c1"))
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `a star value and a revision reach the batch as JSON integers`() = runTest {
        context.getSharedPreferences(EventsStorage.GROVS_STORAGE, Context.MODE_PRIVATE).edit().clear().commit()
        val real = CustomEventsStorage(context)
        val withStorage = CustomEventsManager(
            context = context, grovsContext = grovsContext, grovsService = mockk<IGrovsService>(relaxed = true),
            customEventsStorage = real, startFlushTimer = false,
        )
        withStorage.trackInApp("a1", "in_app_answered",
            mapOf("campaign_id" to "c1", "revision" to 3L, "question_id" to "q1", "type" to "stars", "value" to 4L))

        val gson = GsonBuilder().registerTypeAdapterFactory(LSJsonInstantCompatTypeAdapterFactory()).create()
        val body = gson.toJson(BatchEventsRequest(real.getEvents()))
        assertTrue(body, body.contains("\"value\":4,") || body.contains("\"value\":4}"))
        assertTrue(body, body.contains("\"revision\":3,") || body.contains("\"revision\":3}"))
        withStorage.close()
    }
}
