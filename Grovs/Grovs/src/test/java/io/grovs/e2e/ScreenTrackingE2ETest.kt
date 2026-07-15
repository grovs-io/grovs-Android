package io.grovs.e2e

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.grovs.Grovs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class ScreenTrackingE2ETest {

    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        E2ETestUtils.resetGrovsSingleton()
        E2ETestUtils.setupMockGlInfo()
        E2ETestUtils.setupTestApplication(RuntimeEnvironment.getApplication())
        E2ETestUtils.setupMockUserAgent(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
        )
        mockWebServer = MockWebServer()
        mockWebServer.start()
        E2ETestUtils.enqueueAuthenticationSuccess(mockWebServer)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        E2ETestUtils.resetGrovsSingleton()
    }

    private fun configure(autoTrack: Boolean) {
        Grovs.configure(
            application = RuntimeEnvironment.getApplication(),
            apiKey = "test-key",
            useTestEnvironment = true,
            baseURL = mockWebServer.url("/").toString(),
            autoTrackScreenViews = autoTrack,
        )
    }

    private fun settleAutomaticScreenResolution() {
        val mainLooper = Shadows.shadowOf(Looper.getMainLooper())
        mainLooper.idle()

        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L)
        while (pendingScreenResolutionJobs().isNotEmpty()) {
            mainLooper.idle()
            if (System.nanoTime() >= deadlineNanos) {
                throw AssertionError("Timed out waiting for automatic screen resolution")
            }
            Thread.sleep(10L)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun pendingScreenResolutionJobs(): Collection<Job> {
        val jobsField = Grovs::class.java.getDeclaredField("pendingScreenResolutionJobs")
        jobsField.isAccessible = true
        return (jobsField.get(E2ETestUtils.getGrovsInstance()) as ConcurrentHashMap<*, Job>).values
    }

    @Test
    fun `resuming an activity emits a screen_view when auto-tracking is on`() = runTest {
        configure(autoTrack = true)
        E2ETestUtils.getAuthenticationJob()?.join()

        Robolectric.buildActivity(TestActivity::class.java).create().start().resume()
        settleAutomaticScreenResolution()
        E2ETestUtils.flushCustomEvents()

        val request = E2ETestUtils.awaitRequestFor(mockWebServer, "/api/v1/sdk/event/custom")
        assertNotNull(request)

        val body = JSONObject(request!!.body.readUtf8())
        assertEquals("screen_view", body.getString("event_name"))
        assertEquals("TestActivity", body.getJSONObject("properties").getString("screen_name"))
    }

    @Test
    fun `no screen_view is emitted when auto-tracking is off`() = runTest {
        configure(autoTrack = false)
        E2ETestUtils.getAuthenticationJob()?.join()

        Robolectric.buildActivity(TestActivity::class.java).create().start().resume()
        settleAutomaticScreenResolution()
        E2ETestUtils.flushCustomEvents()

        assertNull(E2ETestUtils.awaitRequestFor(mockWebServer, "/api/v1/sdk/event/custom"))
    }

    @Test
    fun `manual trackScreenView works even when auto-tracking is off`() = runTest {
        configure(autoTrack = false)
        E2ETestUtils.getAuthenticationJob()?.join()

        Grovs.trackScreenView("Checkout", mapOf("step" to 2.0))
        E2ETestUtils.flushCustomEvents()

        val request = E2ETestUtils.awaitRequestFor(mockWebServer, "/api/v1/sdk/event/custom")
        assertNotNull(request)

        val body = JSONObject(request!!.body.readUtf8())
        assertEquals("screen_view", body.getString("event_name"))
        assertEquals("Checkout", body.getJSONObject("properties").getString("screen_name"))
    }

    @Test
    fun `screen aliases rename the reported screen`() = runTest {
        configure(autoTrack = true)
        E2ETestUtils.getAuthenticationJob()?.join()

        Grovs.setScreenAliases(mapOf("TestActivity" to "Home"))
        Robolectric.buildActivity(TestActivity::class.java).create().start().resume()
        settleAutomaticScreenResolution()
        E2ETestUtils.flushCustomEvents()

        val request = E2ETestUtils.awaitRequestFor(mockWebServer, "/api/v1/sdk/event/custom")
        val body = JSONObject(request!!.body.readUtf8())
        assertEquals("Home", body.getJSONObject("properties").getString("screen_name"))
    }

    // An Activity hosting a Fragment must report exactly ONE screen_view, named for the Fragment —
    // without suppression, both onActivityResumed and onFragmentResumed would fire.
    @Test
    fun `an activity hosting a fragment emits exactly one screen_view, named after the fragment`() = runTest {
        configure(autoTrack = true)
        E2ETestUtils.getAuthenticationJob()?.join()

        Robolectric.buildActivity(FragmentHostTestActivity::class.java).create().start().resume()
        settleAutomaticScreenResolution()
        E2ETestUtils.flushCustomEvents()

        val request = E2ETestUtils.awaitRequestFor(mockWebServer, "/api/v1/sdk/event/custom")
        assertNotNull(request)

        val body = JSONObject(request!!.body.readUtf8())
        assertEquals("screen_view", body.getString("event_name"))
        assertEquals("TestFragment", body.getJSONObject("properties").getString("screen_name"))

        // No second screen_view (e.g. for the host Activity, "FragmentHostTestActivity") should follow.
        val secondRequest = E2ETestUtils.awaitRequestFor(mockWebServer, "/api/v1/sdk/event/custom", timeoutMs = 500)
        assertNull(secondRequest)
    }

    // Guard against over-suppression: a plain Activity with no Fragment content must still report
    // its own screen_view, exactly once.
    @Test
    fun `a plain activity with no fragments still emits exactly one screen_view, named after the activity`() = runTest {
        configure(autoTrack = true)
        E2ETestUtils.getAuthenticationJob()?.join()

        Robolectric.buildActivity(TestActivity::class.java).create().start().resume()
        settleAutomaticScreenResolution()
        E2ETestUtils.flushCustomEvents()

        val request = E2ETestUtils.awaitRequestFor(mockWebServer, "/api/v1/sdk/event/custom")
        assertNotNull(request)

        val body = JSONObject(request!!.body.readUtf8())
        assertEquals("screen_view", body.getString("event_name"))
        assertEquals("TestActivity", body.getJSONObject("properties").getString("screen_name"))

        val secondRequest = E2ETestUtils.awaitRequestFor(mockWebServer, "/api/v1/sdk/event/custom", timeoutMs = 500)
        assertNull(secondRequest)
    }

    // On a real device onFragmentResumed fires before onActivityResumed (the opposite of
    // Robolectric's dispatch order), so this test drives the SDK's callback hooks directly in
    // that order. Both callbacks must coalesce into one pending resolution job, which chooses
    // the visible Fragment leaf when it runs.
    @Test
    fun `fragment-first callback order (real-device ordering) still emits exactly one screen_view, named after the fragment`() = runTest {
        // Establish real resumed state before configuring Grovs, so no Grovs lifecycle callback
        // has been registered or invoked for this Activity/Fragment yet.
        val activity = Robolectric.buildActivity(FragmentHostTestActivity::class.java)
            .create().start().resume().get()
        val fragment = activity.supportFragmentManager.fragments.single()
        assertTrue(fragment.isResumed)

        configure(autoTrack = true)
        E2ETestUtils.getAuthenticationJob()?.join()

        E2ETestUtils.dispatchFragmentResumed(activity.supportFragmentManager, fragment)
        E2ETestUtils.dispatchActivityResumed(activity)
        settleAutomaticScreenResolution()

        E2ETestUtils.flushCustomEvents()

        val request = E2ETestUtils.awaitRequestFor(mockWebServer, "/api/v1/sdk/event/custom")
        assertNotNull(request)

        val body = JSONObject(request!!.body.readUtf8())
        assertEquals("screen_view", body.getString("event_name"))
        assertEquals("TestFragment", body.getJSONObject("properties").getString("screen_name"))

        // No second screen_view (e.g. for the host Activity, "FragmentHostTestActivity") should follow.
        val secondRequest = E2ETestUtils.awaitRequestFor(mockWebServer, "/api/v1/sdk/event/custom", timeoutMs = 500)
        assertNull(secondRequest)
    }

    @Test
    fun `nested fragments emit only the visible leaf screen`() = runTest {
        configure(autoTrack = true)
        E2ETestUtils.getAuthenticationJob()?.join()

        Robolectric.buildActivity(NestedFragmentHostTestActivity::class.java)
            .create().start().resume()
        settleAutomaticScreenResolution()
        E2ETestUtils.flushCustomEvents()

        val request = E2ETestUtils.awaitRequestFor(
            mockWebServer,
            "/api/v1/sdk/event/custom",
        )
        assertNotNull(request)

        val body = JSONObject(request!!.body.readUtf8())
        assertEquals(
            "VisibleLeafFragment",
            body.getJSONObject("properties").getString("screen_name"),
        )
        assertNull(
            E2ETestUtils.awaitRequestFor(
                mockWebServer,
                "/api/v1/sdk/event/custom",
                timeoutMs = 500,
            )
        )
    }
}
