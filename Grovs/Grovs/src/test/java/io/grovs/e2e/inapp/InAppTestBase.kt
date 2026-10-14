package io.grovs.e2e.inapp

import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.google.gson.JsonObject
import io.grovs.Grovs
import io.grovs.e2e.E2ETestUtils
import io.grovs.e2e.contract.ContractTestBase
import io.grovs.e2e.contract.InAppBackend
import io.grovs.inapp.InAppController
import io.grovs.inapp.InAppFileStore
import io.grovs.inapp.InAppHash
import io.grovs.inapp.InAppSynced
import io.grovs.inapp.ui.InAppDialogPresenter
import io.grovs.inapp.ui.InAppHtmlDocument
import io.grovs.inapp.ui.InAppHtmlView
import io.grovs.inapp.ui.InAppJavascriptChannel
import io.grovs.inapp.ui.InAppWindow
import io.grovs.utils.InstantCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives the real in-app presenter against [InAppBackend]. The base campaign is "c1", shown by
 * `Grovs.track("cart_viewed")`. Every test still fails when the SDK breaks the backend contract.
 */
internal abstract class InAppTestBase : ContractTestBase() {

    protected val inApp = InAppBackend()
    protected val recorder = InAppCallbackRecorder()

    /** The key the SDK names its in-app folders by: test environment plus the configured api key. */
    protected val storeKey = "test_test-api-key"

    /** A first sync under full-suite load can take well over the default 5 s wall-clock wait. */
    private val syncTimeoutMs = 20_000L

    @Before
    fun enableInApp() {
        application.noBackupFilesDir.resolve("grovs/in_app").deleteRecursively()
        application.cacheDir.resolve("grovs-in-app").deleteRecursively()
        contract.inApp = inApp
        Grovs.setInAppListener(recorder)
    }

    // Start and restart

    /** Puts [campaigns] on the server, authenticates, resumes the launcher and waits until the first sync is stored. */
    protected fun start(vararg campaigns: JsonObject) {
        inApp.campaigns += campaigns
        authenticated()
        resumeHost()
        awaitStoredSync("the first in-app sync to be stored")
    }

    /**
     * [start]s, then lets the foreground hook finish. It syncs too, unless the first sync is still running, so a
     * test that counts syncs needs this quiet moment first.
     */
    protected fun startSettled(vararg campaigns: JsonObject) {
        start(*campaigns)
        repeat(10) { Thread.sleep(100); pump() }
        pumpUntil("the SDK to stop syncing") { !sdkSyncing() }
    }

    /** [start]s with [campaign], tracks [event] and waits until it is on screen. */
    protected fun showOne(campaign: JsonObject, event: String = "cart_viewed") {
        start(campaign)
        Grovs.track(event)
        pumpUntil("the message to show") { recorder.shown.size == 1 }
    }

    /** Taps the close button and waits for the dismissal callback. */
    protected fun closeShown() {
        val dismissed = recorder.dismissed.size
        tapClose()
        pumpUntil("the message to close") { recorder.dismissed.size > dismissed }
    }

    /**
     * Home button, then long enough on the main looper for ProcessLifecycleOwner to report the stop
     * (it waits 700 ms to rule out a configuration change). [background] alone is not yet a background.
     */
    protected fun backgroundApp() {
        background()
        idleFor(1_000)
    }

    /** The app goes to the background and comes back with a fresh launcher; returns once the SDK has used the sync answer. */
    protected fun reopen() = reopenAfterBackground(minutesAway = 0)

    /**
     * Like [reopen], but the app was away for [minutesAway] minutes, so the SDK may start a new session on the same
     * running controller (a background over 30 minutes).
     */
    protected fun reopenAfterBackground(minutesAway: Long) {
        val syncs = inApp.syncBodies.size
        backgroundApp()
        // backgroundedAt has a private setter and the real wait would need that much wall-clock time.
        if (minutesAway > 0) {
            val sdkContext = manager().grovsContext
            sdkContext.javaClass.getDeclaredField("backgroundedAt").apply { isAccessible = true }
                .set(sdkContext, InstantCompat.now().minusMillis(minutesAway * 60_000))
        }
        foreground(Intent())
        resumeHost()
        awaitSyncHandled("a sync after reopening", syncs)
    }

    /**
     * Cold start: the app goes to the background, the process dies, the SDK is configured again with the same listener and the
     * launcher comes back. Stored files survive. Returns once the SDK has used the sync answer, whatever it was.
     * [whileDead] runs between the process death and the new configure, to change what is on disk.
     */
    protected fun restart(whileDead: () -> Unit = {}) {
        // A sync still running would be counted as the new process's sync.
        awaitSync("the running sync to finish") { !sdkSyncing() }
        val syncs = inApp.syncBodies.size
        backgroundApp()
        simulateProcessDeath()
        whileDead()
        Grovs.setInAppListener(recorder)
        configure()
        foreground(Intent())
        resumeHost()
        awaitSyncHandled("a sync after restarting", syncs)
    }

    /**
     * Waits until the server got a sync request beyond the first [syncsBefore] and the SDK has
     * finished with its answer (applied it, or given up on a failure).
     */
    protected fun awaitSyncHandled(description: String, syncsBefore: Int) {
        awaitSync(description) { inApp.syncBodies.size > syncsBefore && !sdkSyncing() }
        pump()
    }

    /** Waits up to [syncTimeoutMs]; a timeout says where the sync stood. */
    private fun awaitSync(description: String, condition: () -> Boolean) {
        try {
            pumpUntil(description, syncTimeoutMs, condition)
        } catch (e: AssertionError) {
            val state = "syncs: ${inApp.syncBodies.size}, syncing: ${runCatching { sdkSyncing() }.getOrNull()}, " +
                "auth: ${runCatching { manager().authenticationState }.getOrNull()}"
            throw AssertionError("${e.message} ($state)", e)
        }
    }

    // The request reaches the server before the SDK reads the answer; the controller's flag is the only signal it is done.
    private fun sdkSyncing(): Boolean =
        InAppController::class.java.getDeclaredField("syncing").apply { isAccessible = true }
            .getBoolean(manager().inApp.controller)

    private val firstSyncGate = CountDownLatch(1)

    @After
    fun releaseHeldSync() = firstSyncGate.countDown()

    /** The first sync is held on the server until [releaseFirstSync], so the SDK decides app_open while it is in flight. */
    protected fun holdFirstSync() {
        val first = AtomicBoolean(true)
        backend.answer("in_app/sync") { seen ->
            if (first.getAndSet(false)) firstSyncGate.await(20, TimeUnit.SECONDS)
            inApp.syncAnswer(InAppBackend.parse(seen.body.ifBlank { "{}" }))
        }
    }

    protected fun releaseFirstSync() = firstSyncGate.countDown()

    // InAppSynced is the stored sync; store writes run on the SDK's own executor, so it is the only completion signal.
    protected fun awaitStoredSync(description: String = "a stored in-app sync") {
        awaitSync(description) { InAppSynced.load(store()) != null }
        pump()
    }

    // Store

    protected fun store() = InAppFileStore(application.noBackupFilesDir, storeKey)

    /** The folder the SDK keeps its in-app state in; seeding or damaging it fakes an old run. */
    protected fun storeFolder() = File(application.noBackupFilesDir, "grovs/in_app/${InAppHash.short(storeKey)}")

    // Native messages

    // The presenter has no public handle; the private field is the only way to reach the drawn views.
    protected fun presenter(): InAppDialogPresenter =
        Grovs::class.java.getDeclaredField("inAppPresenter").apply { isAccessible = true }
            .get(E2ETestUtils.getGrovsInstance()) as InAppDialogPresenter

    /** The campaign id of the last message shown. */
    protected fun shownId(): String? = recorder.shown.lastOrNull()

    protected fun window(): InAppWindow = presenter().shownWindow ?: error("no native message on screen")
    protected fun root(): View = window().dialog.window!!.decorView
    protected fun <T : View> view(id: Int): T = root().findViewById<T>(id) ?: error("view $id not found")
    protected fun tapTag(tag: String) { (root().findViewWithTag<View>(tag) ?: error("no view tagged $tag")).performClick(); pump() }
    protected fun tapButton(id: String) = tapTag("grovs-in-app-button-$id")
    /** A star ("1".."5") or a choice option id. */
    protected fun tapAnswer(id: String) = tapTag("grovs-in-app-answer-$id")
    protected fun tapClose() { view<View>(io.grovs.R.id.grovs_in_app_close).performClick(); pump() }
    protected fun pressBack() { window().dialog.onBackPressedDispatcher.onBackPressed(); pump() }
    /** The alert template's dialog. */
    protected fun alert(): AlertDialog = ShadowDialog.getLatestDialog() as AlertDialog

    // HTML messages

    protected fun page(): InAppHtmlView = presenter().shownPage ?: error("no html page on screen")

    // The page's bridge channel and per-show token are the only way to speak as the page without a real WebView.
    protected fun fromPage(type: String, args: String = "{}", id: String = "1") {
        val page = page()
        val channel = shadowOf(page.webView!!).getJavascriptInterface(InAppHtmlDocument.CHANNEL) as InAppJavascriptChannel
        channel.postMessage("""{"v":1,"token":"${page.session.token}","id":"$id","type":"$type","args":$args}""")
        pump()
    }

    /** The last script the SDK ran in the page. */
    protected fun lastToPage(): String? = shadowOf(page().webView!!).lastEvaluatedJavascript

    /** The page says it is ready and how tall it is, which puts it on screen. */
    protected fun showPage(height: Int = 80) {
        fromPage("ready", """{"missing":false}""", "r")
        fromPage("height", """{"value":$height}""", "h")
    }

    // Main looper time (the SDK's in-app timers)

    protected fun idleFor(ms: Long) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); pump() }

    /** Moves main looper time in 100 ms steps until [condition] holds, failing after [maxMs]. Each step gives the network a moment. */
    protected fun idleUntil(description: String, maxMs: Long, condition: () -> Boolean) {
        var waited = 0L
        while (!condition() && waited < maxMs) { idleFor(100); Thread.sleep(5); waited += 100 }
        if (!condition()) throw AssertionError("timed out waiting for $description")
    }

    // Reported events

    /** Every event with this name the backend received so far, without flushing. */
    protected fun sent(name: String): List<JSONObject> = contract.batchEvents().filter { it.optString("event_name") == name }

    /** Flushes queued events and returns every sent event with this name. For negative checks, after a positive control. */
    protected fun reported(name: String): List<JSONObject> {
        flushEvents()
        return sent(name)
    }

    /** Flushes until at least [count] events with this name reached the backend, then returns all of them. */
    protected fun reportedWhen(name: String, count: Int = 1, timeoutMs: Long = 5_000): List<JSONObject> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (sent(name).size < count) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timed out waiting for $count $name, sent: ${contract.batchEvents().map { it.optString("event_name") }}")
            }
            flushEvents()
        }
        return sent(name)
    }

    /** Sends the queued events now and waits for the request to finish. */
    protected fun flushEvents() {
        // The attribution lookup that holds events runs on the driver (a new activity starts one), so pump while waiting.
        pumpUntil("custom events to be released") { runCatching { E2ETestUtils.awaitEventsReleased(timeoutMs = 0) }.isSuccess }
        val flush = CoroutineScope(driver + SupervisorJob()).launch { manager().flushCustomEvents() }
        pumpUntil("the event flush") { flush.isCompleted }
        pump()
    }

    // Clock and images

    // The sync answer's server_time is how the SDK corrects its clock.
    /** The server clock runs [ms] ahead, which moves the SDK's corrected "now" at the next sync. */
    protected fun serverAhead(ms: Long) { inApp.serverNowMs = { System.currentTimeMillis() + ms } }

    // Image downloads need https, which MockWebServer cannot serve here; a cached file counts as ready.
    protected fun seedImage(url: String) {
        val dir = File(application.cacheDir, "grovs-in-app/${InAppHash.short(storeKey)}").apply { mkdirs() }
        val png = ByteArrayOutputStream().also { Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, InAppHash.sha256Hex(url)).writeBytes(png.toByteArray())
    }
}
