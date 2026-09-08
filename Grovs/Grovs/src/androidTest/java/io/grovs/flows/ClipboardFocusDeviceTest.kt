package io.grovs.flows

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.grovs.Grovs
import io.grovs.handlers.GrovsManager
import io.grovs.storage.EventsStorage
import kotlinx.coroutines.Job
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** A real startup modal transfers focus to another window without stopping the activity. */
class StartupDialogActivity : Activity() {
    lateinit var startupDialog: Dialog
    var starts = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Onboarding" })
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
            ClipData.newPlainText("Referral", intent.getStringExtra("clipboard_url")))
        startupDialog = Dialog(this).apply {
            setContentView(TextView(this@StartupDialogActivity).apply { text = "Read this before continuing" })
            setCancelable(false)
            show()
        }
    }
    override fun onStart() {
        super.onStart()
        starts++
        Grovs.onStart(this)
    }
}

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ClipboardFocusDeviceTest {
    @Test
    fun referralMatchesWhenStartupDialogClosesWithinFocusTimeout() = runStartupDialogFlow(500)

    @Test
    fun referralRecoversWhenStartupDialogClosesAfterFocusTimeout() = runStartupDialogFlow(4_500)

    private fun runStartupDialogFlow(dialogReadingMs: Long) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        app.getSharedPreferences(EventsStorage.GROVS_STORAGE, Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("grovs_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val copied = "https://demo.sqd.link/referral?gd=focus-device"
        val resolved = "https://demo.sqd.link/resolved"
        val statusReached = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val received = AtomicReference<String?>()
        val matches = AtomicInteger()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = when (request.requestUrl?.encodedPath) {
                        "/api/v1/sdk/device_for_vendor_id" -> """{"last_seen":null}"""
                        "/api/v1/sdk/authenticate" -> """{"linksquared":"focus-test-device","uri_scheme":"testapp"}"""
                        "/api/v1/sdk/clipboard_status" -> {
                            statusReached.countDown()
                            """{"clipboard_active":true}"""
                        }
                        "/api/v1/sdk/data_for_device_and_url" -> {
                            val url = JSONObject(request.body.readUtf8()).optString("url")
                            if (url == copied) {
                                matches.incrementAndGet()
                                """{"link":"$resolved","data":{"destination":"referral"}}"""
                            } else """{"link":null,"data":null}"""
                        }
                        else -> "{}"
                    }
                    return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            start()
        }
        var scenario: ActivityScenario<StartupDialogActivity>? = null
        try {
            Grovs.configure(app, "focus-test-key", true, server.url("/").toString())
            Grovs.setOnDeeplinkReceivedListener(null) { details ->
                received.set(details.link)
                delivered.countDown()
            }
            scenario = ActivityScenario.launch(Intent(app, StartupDialogActivity::class.java)
                .setData(Uri.parse("https://demo.sqd.link/no-match"))
                .putExtra("clipboard_url", copied).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue("The real SDK must reach clipboard eligibility", statusReached.await(15, TimeUnit.SECONDS))
            // Exercise both a quick dismissal and a person reading past the 3-second focus timeout.
            SystemClock.sleep(dialogReadingMs)
            assertEquals("No clipboard match before focus returns", 0, matches.get())
            scenario.onActivity {
                assertFalse("The modal must actually take focus from the activity", it.hasWindowFocus())
                assertEquals(1, it.starts)
                it.startupDialog.dismiss()
            }
            assertTrue("Restoring focus should recover the referral without another app launch", delivered.await(25, TimeUnit.SECONDS))
            assertEquals(resolved, received.get())
            assertEquals(1, matches.get())
            scenario.onActivity { assertEquals("Recovery must not depend on another onStart", 1, it.starts) }
        } finally {
            scenario?.close()
            val instance = Grovs::class.java.getDeclaredField("instance").apply { isAccessible = true }.get(null)
            (Grovs::class.java.getDeclaredField("grovsManager").apply { isAccessible = true }.get(instance) as? GrovsManager)?.close()
            (Grovs::class.java.getDeclaredField("authenticationJob").apply { isAccessible = true }.get(instance) as? Job)?.cancel()
            val observer = Grovs::class.java.getDeclaredField("applicationLifecycleObserver").apply { isAccessible = true }
                .get(instance) as Application.ActivityLifecycleCallbacks
            app.unregisterActivityLifecycleCallbacks(observer)
            server.shutdown()
        }
    }
}
