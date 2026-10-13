package io.grovs.inapp

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.grovs.inapp.model.InAppActionType
import io.grovs.inapp.model.InAppAnswer
import io.grovs.inapp.model.InAppCampaign
import io.grovs.inapp.model.InAppDecision
import io.grovs.inapp.model.InAppDefinition
import io.grovs.inapp.model.InAppDevice
import io.grovs.inapp.model.InAppEvent
import io.grovs.inapp.model.InAppEventType
import io.grovs.inapp.model.InAppGlobalCaps
import io.grovs.inapp.model.InAppJson
import io.grovs.inapp.model.InAppPersonalize
import io.grovs.inapp.model.InAppTemplate
import java.net.URI
import java.util.UUID

/** Connects the engine to the SDK: storing, syncing, showing and reporting. Main thread only. */
internal class InAppController(private val deps: Dependencies) {

    class Dependencies(
        val store: InAppStoring,
        val presenter: InAppPresenter,
        val images: InAppImages,
        /** Everything about the device except what the sync supplies. */
        val device: () -> InAppDevice,
        /** [done] is called on the main thread. */
        val sync: (body: JsonObject, done: (ok: Boolean, body: JsonObject?) -> Unit) -> Unit,
        /** Queues a reserved in-app event under a fixed id, so a view's id is its event's id. */
        val report: (eventId: String, name: String, properties: JsonObject) -> Unit,
        val sessionId: () -> String,
        /** False while the SDK is disabled, retired or not yet authenticated. */
        val canRun: () -> Boolean,
        val isActive: () -> Boolean,
        val now: () -> Long = { System.currentTimeMillis() },
        val random: () -> Double = { Math.random() },
        val schedule: (delayMillis: Long, work: () -> Unit) -> InAppCancellable,
        val sdkVersion: String,
        /** A message that could not be drawn, for the host's failure callback. */
        val fail: (campaignId: String, message: String) -> Unit = { _, _ -> },
        /** A custom event raised by an html page. */
        val track: (name: String, properties: JsonObject) -> Unit = { _, _ -> },
        val setAttribute: (key: String, value: JsonElement) -> Unit = { _, _ -> },
    )

    companion object {
        const val SYNC_EVERY_MS = 15 * 60_000L
        const val FORGET_AFTER_MS = 14 * 86_400_000L
        const val VIEWS_KEPT = 5_000
        const val TEST_TRIES = 10
        const val TEST_RETRY_MS = 1_000L
        const val OPEN_WAIT_MS = 2_000L
        const val JITTER_MS = 10_000L
    }

    /** [only] is set for a message that waited out a delay: show that campaign or nothing. */
    private class Waiting(val event: InAppEvent, val priority: Int, val only: String?)
    private class Delayed(val timer: InAppCancellable, val priority: Int)

    var settings = InAppSettings()

    private var synced = InAppSynced()
    private var kept = InAppKept()
    private var saved: InAppKept? = null
    private var engine: InAppEngine? = null
    private var running = false
    private var showing: String? = null
    private var waiting: Waiting? = null
    private var delayed: Delayed? = null
    private var screen: String? = null
    private var syncing = false
    private var syncAgain = false
    private val afterSync = mutableListOf<() -> Unit>()
    private var syncTimer: InAppCancellable? = null
    private var periodic: InAppCancellable? = null
    private val reported = mutableSetOf<String>()
    /** Messages sent from the dashboard to look at; shown as they are, counted nowhere. */
    private val tests = mutableListOf<JsonElement>()
    private var testTries = 0
    private var testTimer: InAppCancellable? = null
    private var openTimer: InAppCancellable? = null
    private val held = mutableListOf<InAppEvent>()
    /** The screen the app opened on, tracked before in-app could run. */
    private var landing: InAppEvent? = null
    private var lastSyncAttempt: Long? = null
    private var generation = 0

    val isRunning: Boolean get() = running

    /** The app's store page as the last sync gave it; https only. */
    val storeUrl: String?
        get() = synced.storeUrl?.takeIf { url ->
            val parsed = try { URI(url) } catch (e: Exception) { null }
            parsed?.scheme == "https" && !parsed.host.isNullOrEmpty()
        }

    // Lifecycle

    /** Called once authentication has succeeded. */
    fun start() {
        if (running || !deps.canRun()) return
        running = true
        generation += 1
        synced = InAppSynced.load(deps.store) ?: InAppSynced()
        kept = InAppKept.load(deps.store) ?: InAppKept()
        saved = null
        forgetIfOld()
        engine = build()
        reportUnsupported()
        deps.images.preload(synced.campaigns)
        followSession()
        sync()
        landing?.let { first -> if (syncing) hold(first) else handle(first) }
        landing = null
    }

    fun stop() {
        running = false
        generation += 1
        syncTimer?.cancel()
        periodic?.cancel()
        openTimer?.cancel()
        delayed?.timer?.cancel()
        testTimer?.cancel()
        syncTimer = null
        periodic = null
        openTimer = null
        delayed = null
        testTimer = null
        held.clear()
        afterSync.clear()
        syncing = false
        syncAgain = false
        waiting = null
        tests.clear()
        deps.presenter.dismiss()
    }

    fun pause() {
        settings.paused = true
    }

    fun resume() {
        settings.paused = false
        showWaiting()
    }

    /** Drops what is waiting and stays paused; with [dismissCurrent], also closes what is on screen. */
    fun discard(dismissCurrent: Boolean) {
        settings.paused = true
        waiting = null
        delayed?.timer?.cancel()
        delayed = null
        openTimer?.cancel()
        openTimer = null
        held.clear()
        if (dismissCurrent) deps.presenter.dismiss()
    }

    fun setExcludedScreens(screens: List<String>) {
        settings.excluded = screens.map { it.lowercase() }.toSet()
        showWaiting()
    }

    fun onForeground() {
        if (!active) return
        sync()
        showWaiting()
    }

    /** A delay does not outlive the visit: what was counting down, or had just finished, is dropped. */
    fun onBackground() {
        delayed?.timer?.cancel()
        delayed = null
        if (waiting?.only != null) waiting = null
    }

    /** The batch response's two stamps; a change in either means the campaigns moved. */
    fun onBatchStamps(campaignsChangedAt: String?, userChangedAt: String?) {
        if (!active) return
        val moved = (campaignsChangedAt != null && campaignsChangedAt != synced.campaignsChangedAt) ||
            (userChangedAt != null && userChangedAt != synced.userChangedAt)
        if (!moved || syncTimer != null) return
        // Spread out, so launching a campaign does not bring every device to the server at once.
        syncTimer = deps.schedule((deps.random() * JITTER_MS).toLong()) {
            syncTimer = null
            if (syncing) syncAgain = true else sync()
        }
    }

    /** Raised once per SDK session by the caller. */
    fun onAppOpen() = onEvent(InAppEvent(InAppEventType.APP_OPEN))

    fun onEvent(event: InAppEvent) {
        // Remembered even before in-app runs, so a message never opens over a screen it was told to avoid.
        if (event.type == InAppEventType.SCREEN_VIEW) screen = event.name?.lowercase()
        if (!active) {
            if (event.type == InAppEventType.SCREEN_VIEW && !running) landing = event
            return
        }
        val last = lastSyncAttempt
        if (last == null || deps.now() - last > SYNC_EVERY_MS) sync()

        if (event.type == InAppEventType.APP_OPEN && syncing) {
            hold(event)
            return
        }
        handle(event)
        if (event.type == InAppEventType.SCREEN_VIEW) showWaiting()
    }

    // Deciding

    private fun handle(event: InAppEvent) {
        followSession()
        val engine = engine ?: return
        engine.update(device())
        val decision = engine.peek(event, now())
        persist()
        explain(event, decision)
        val id = decision.show ?: return

        val priority = engine.campaign(id)?.priority ?: 0
        val seconds = decision.delay
        if (seconds != null && seconds > 0) {
            delay(event, id, priority, seconds)
            return
        }
        if (blocked) {
            val current = waiting
            if (current == null || priority > current.priority) waiting = Waiting(event, priority, null)
            return
        }
        show(decision, event)
    }

    /** Holds one message until its delay has passed; then it is decided again, as a waiting one is. */
    private fun delay(event: InAppEvent, only: String, priority: Int, seconds: Int) {
        val current = delayed
        if (current != null && current.priority >= priority) return
        current?.timer?.cancel()
        val timer = deps.schedule(seconds * 1_000L) {
            delayed = null
            if (active) {
                val other = waiting
                if (other == null || priority > other.priority) waiting = Waiting(event, priority, only)
                showWaiting()
            }
        }
        delayed = Delayed(timer, priority)
    }

    /** Whenever the screen may have become free: what waited first, then a test sent from the dashboard. */
    private fun showWaiting() {
        showHeld()
        showTest()
    }

    private fun showHeld() {
        val held = waiting ?: return
        val engine = engine ?: return
        if (!active || blocked) return
        waiting = null
        engine.update(device())
        val decision = engine.recheck(held.event, now())
        explain(held.event, decision)
        // A delayed message shows only if it still wins; whatever beat it had its own chance at the trigger.
        if (held.only != null && decision.show != held.only) return
        val id = decision.show ?: return
        // What waited may now be another campaign; if that one has a delay, it waits it out like any other.
        val seconds = decision.delay
        if (held.only == null && seconds != null && seconds > 0) {
            delay(held.event, id, engine.campaign(id)?.priority ?: 0, seconds)
            return
        }
        show(decision, held.event)
    }

    private fun show(decision: InAppDecision, event: InAppEvent) {
        val engine = engine ?: return
        val id = decision.show ?: return
        val campaign = engine.campaign(id) ?: return
        val rendered = decision.rendered ?: return
        val listener = settings.listener
        if (listener != null && !listener.shouldShowInAppMessage(id)) return
        // The host's answer may have stopped the SDK, paused it or left the app.
        if (!active || blocked) return
        showing = id

        val base = base(campaign)
        val shownIn = generation
        val html = campaign.template == InAppTemplate.HTML
        var answer: InAppAnswer? = null
        var clicked = false
        var counted = false
        val presentation = InAppPresentation(
            campaign = campaign,
            rendered = rendered,
            storeUrl = storeUrl,
            user = device().user,
            event = event.properties,
            onAnswer = { value ->
                if (shownIn == generation) {
                    answer = value
                    answered(campaign, base, value)
                }
            },
            onComment = { comment ->
                val given = answer
                if (shownIn == generation && given != null) {
                    val properties = merged(base, answerProperties(campaign, given))
                    properties.addProperty("comment", comment)
                    deps.report(newEventId(), "in_app_commented", properties)
                }
            },
            onTrack = { name, properties ->
                if (shownIn == generation) {
                    deps.track(name, merged(properties).also { it.addProperty("campaign_id", id) })
                    if (!clicked) {
                        clicked = true
                        reportClick(campaign, base, null, "track", null)
                    }
                }
            },
            onAttribute = { key, value -> if (shownIn == generation) deps.setAttribute(key, value) },
            canShow = if (html) {
                { shownIn == generation && active && !hostBlocks }
            } else null,
            onShown = if (html) {
                {
                    if (shownIn == generation && !counted) {
                        counted = true
                        countView(campaign, base)
                    }
                }
            } else null,
        )
        val drawn = deps.presenter.present(presentation) { outcome ->
            showing = null
            if (shownIn != generation) return@present
            if (outcome is InAppOutcome.Unshown) {
                showWaiting()
                return@present
            }
            // An html page's first track already was its click; an open after it only closes the message.
            if (!(outcome is InAppOutcome.Clicked && clicked)) settle(campaign, base, outcome)
            // A link, the store or the review prompt takes the screen; what waits shows when the person is back.
            if (outcome is InAppOutcome.Clicked &&
                (outcome.action.type == InAppActionType.OPEN_URL || outcome.action.type == InAppActionType.OPEN_STORE)
            ) return@present
            if (outcome is InAppOutcome.Answered) {
                val stars = (outcome.value as? InAppAnswer.Stars)?.number
                val from = campaign.content.question?.reviewFrom
                if (stars != null && from != null && stars >= from) return@present
            }
            showWaiting()
        }
        if (!drawn) {
            showing = null
            deps.fail(id, "In-app message $id could not be shown: the app has no screen to draw it on.")
            return
        }
        if (!html) countView(campaign, base)
    }

    /** Counted only once drawn, so a message nobody saw uses up no limit and reports no view. */
    private fun countView(campaign: InAppCampaign, base: JsonObject) {
        val viewId = engine?.record(campaign.id, now()) ?: return
        persist()
        deps.report(viewId, "in_app_viewed", merged(base).also { it.addProperty("template", campaign.template.wire) })
        settings.listener?.onInAppMessageShown(campaign.id)
    }

    // Test messages

    /** Each test has its own ten tries. */
    private fun nextTest() {
        tests.removeAt(0)
        testTries = 0
    }

    private fun takeTests(body: JsonObject) {
        val sent = body.get("test_messages")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
        if (sent.size() == 0) return
        // Added to what still waits: the server hands each test over once, so replacing would lose one.
        tests.addAll(sent)
        deps.images.preload(tests)
        showTest()
    }

    /** No rule or limit applies and nothing is counted; it still waits for a free screen and an unpaused host. */
    private fun showTest() {
        val raw = tests.firstOrNull() ?: return
        if (!active || blocked) return
        if (!deps.images.ready(raw)) {
            if (testTimer != null) return
            testTries += 1
            if (testTries > TEST_TRIES) {
                InAppLog.info("In-app test message dropped: its pictures did not load")
                nextTest()
                showTest()
                return
            }
            deps.images.preload(listOf(raw))
            testTimer = deps.schedule(TEST_RETRY_MS) {
                testTimer = null
                showTest()
            }
            return
        }
        nextTest()
        val campaign = InAppDefinition.readCampaign(raw)
        val outcome = campaign?.let { InAppPersonalize.render(it, InAppEvent(InAppEventType.APP_OPEN), device().user) }
        val rendered = (outcome as? InAppPersonalize.Outcome.Rendered)?.rendered
        if (campaign == null || rendered == null) {
            InAppLog.info("In-app test message cannot be shown by this SDK")
            return
        }
        showing = campaign.id
        val shownIn = generation
        val test = InAppPresentation(
            campaign = campaign,
            rendered = rendered,
            isTest = true,
            storeUrl = storeUrl,
            user = device().user,
            onTrack = { name, _ -> InAppLog.info("In-app test message tracked $name; not sent") },
            onAttribute = { key, _ -> InAppLog.info("In-app test message set attribute $key; not sent") },
            canShow = { shownIn == generation && active && !hostBlocks },
        )
        val drawn = deps.presenter.present(test) {
            showing = null
            if (shownIn == generation) showWaiting()
        }
        if (!drawn) showing = null
    }

    // Reporting

    private fun base(campaign: InAppCampaign) = JsonObject().apply {
        addProperty("campaign_id", campaign.id)
        addProperty("revision", campaign.revision)
    }

    private fun answerProperties(campaign: InAppCampaign, answer: InAppAnswer) = JsonObject().apply {
        addProperty("question_id", campaign.content.question?.id ?: "")
        addProperty("type", campaign.content.question?.type?.wire ?: "")
        when (answer) {
            is InAppAnswer.Stars -> addProperty("value", answer.number)
            is InAppAnswer.Option -> addProperty("value", answer.id)
        }
    }

    /** Reported at the tap, so an answer counts even if the app is closed during the comment. */
    private fun answered(campaign: InAppCampaign, base: JsonObject, answer: InAppAnswer) {
        engine?.answer(campaign.id)
        persist()
        val properties = answerProperties(campaign, answer)
        deps.report(newEventId(), "in_app_answered", merged(base, properties))
        // An answer is an event too, so another campaign can follow it.
        handle(InAppEvent(InAppEventType.EVENT, "in_app_answered", merged(properties).also { it.addProperty("campaign_id", campaign.id) }))
    }

    private fun settle(campaign: InAppCampaign, base: JsonObject, outcome: InAppOutcome) {
        when (outcome) {
            is InAppOutcome.Answered -> settings.listener?.onInAppMessageAnswered(campaign.id, outcome.value.value, outcome.comment)
            is InAppOutcome.Unshown -> Unit
            is InAppOutcome.Clicked ->
                reportClick(campaign, base, outcome.buttonId, outcome.action.type.wire, outcome.action.payload)
            is InAppOutcome.Dismissed -> {
                deps.report(newEventId(), "in_app_dismissed", merged(base).also { it.addProperty("reason", outcome.reason) })
                settings.listener?.onInAppMessageDismissed(campaign.id, outcome.reason)
            }
        }
    }

    private fun reportClick(campaign: InAppCampaign, base: JsonObject, buttonId: String?, action: String, payload: JsonObject?) {
        val properties = merged(base)
        properties.addProperty("action", action)
        // A tap on the message itself has no button, so it names none.
        buttonId?.let { properties.addProperty("button_id", it) }
        deps.report(newEventId(), "in_app_clicked", properties)
        settings.listener?.onInAppMessageClicked(campaign.id, buttonId, payload?.let(InAppHostValues::toMap))
    }

    private val blocked: Boolean get() = showing != null || hostBlocks

    /** Paused, in the background or on an excluded screen. */
    private val hostBlocks: Boolean
        get() = settings.paused || !deps.isActive() || (screen?.let { it in settings.excluded } ?: false)

    /** What the app opened with waits for the sync start() began, so a campaign paused overnight does not show. */
    private fun hold(event: InAppEvent) {
        held.add(event)
        if (held.size != 1) return
        val release: () -> Unit = release@{
            if (held.isEmpty()) return@release
            // The opening first, then the screen it opened on.
            val order = held.filter { it.type == InAppEventType.APP_OPEN } + held.filter { it.type != InAppEventType.APP_OPEN }
            held.clear()
            openTimer?.cancel()
            openTimer = null
            if (!active) return@release
            order.forEach(::handle)
            showWaiting()
        }
        openTimer = deps.schedule(OPEN_WAIT_MS, release)
        afterSync.add(release)
    }

    // Syncing

    private fun sync() {
        if (!active || syncing) return
        syncing = true
        lastSyncAttempt = deps.now()
        keepSyncing()
        val sentIn = generation
        val sent = deps.now()
        val body = JsonObject().apply {
            add("campaigns_changed_at", synced.campaignsChangedAt?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
            add("user_changed_at", synced.userChangedAt?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
            addProperty("format", 1)
            addProperty("sdk_version", deps.sdkVersion)
        }
        deps.sync(body) done@{ ok, response ->
            if (sentIn != generation) return@done
            if (ok && response != null) {
                apply(response, sent)
                takeTests(response)
            }
            syncing = false
            val waiters = afterSync.toList()
            afterSync.clear()
            waiters.forEach { it() }
            if (syncAgain) {
                syncAgain = false
                sync()
            }
        }
    }

    /** While the app is in use a sync is never more than fifteen minutes away, events or not. */
    private fun keepSyncing() {
        periodic?.cancel()
        periodic = deps.schedule(SYNC_EVERY_MS) {
            periodic = null
            if (deps.isActive()) sync()
        }
    }

    private fun apply(body: JsonObject, sent: Long) {
        val serverTime = InAppDefinition.readTimestamp(body.get("server_time")) ?: return
        // Half the round trip is the best estimate of when the server read its clock.
        val middle = (sent + deps.now()) / 2.0
        // In every answer, because the store may learn the app's id long after the campaigns last changed.
        val hadStore = storeUrl != null
        synced = synced.copy(clockOffset = Math.round(serverTime - middle), storeUrl = InAppJson.string(body.get("store_url")))
        val changed = InAppJson.bool(body.get("unchanged")) != true
        if (changed) {
            val campaigns = body.get("campaigns")?.takeIf { it.isJsonArray }?.asJsonArray?.toList() ?: return
            val caps = body.get("global_caps")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
            synced = synced.copy(
                campaigns = campaigns,
                globalCaps = InAppGlobalCaps(session = positive(caps.get("session")), day = positive(caps.get("day"))),
                firstSeenAt = InAppJson.string(body.get("first_seen_at")),
                campaignsChangedAt = InAppJson.string(body.get("campaigns_changed_at")) ?: synced.campaignsChangedAt,
                userChangedAt = InAppJson.string(body.get("user_changed_at")) ?: synced.userChangedAt,
            )
            // A test still waiting keeps its pictures too.
            deps.images.keepOnly(campaigns + tests)
        }
        // A store address that came or went changes which campaigns this device can show.
        if (changed || hadStore != (storeUrl != null)) {
            engine?.let { kept = kept.copy(state = it.state) }
            engine = build()
            reportUnsupported()
        }
        // After every sync, so a picture that failed to load is tried again.
        deps.images.preload(synced.campaigns)
        synced.save(deps.store)
        engine?.markSynced(serverTime)
        persist(force = true)
    }

    private fun positive(value: JsonElement?): Int? = InAppJson.whole(value)?.takeIf { it > 0 }

    // State

    /** Read before every decision: a name or a permission changed since the sync must show at once. */
    private fun device(): InAppDevice {
        val device = deps.device()
        // Without an address there is no store page to open, so such a campaign is not for this device.
        val unsupported = if (storeUrl == null) device.unsupportedActions + InAppActionType.OPEN_STORE.wire else device.unsupportedActions
        return device.copy(
            firstSeenAt = synced.firstSeenAt?.let { InAppDefinition.readTimestamp(JsonPrimitive(it)) },
            unsupportedActions = unsupported,
        )
    }

    /** Fourteen days without a sync: the campaigns are too old to trust. Views stay, the limits need them. */
    private fun forgetIfOld() {
        val last = kept.state.syncedAt
        if (last <= 0 || now() - last <= FORGET_AFTER_MS || synced.campaigns.isEmpty()) return
        synced = synced.copy(campaigns = emptyList(), campaignsChangedAt = null, userChangedAt = null)
        synced.save(deps.store)
        deps.images.keepOnly(emptyList())
    }

    /** Once per revision, so a campaign this SDK cannot show is noticed without filling the log. */
    private fun reportUnsupported() {
        for ((id, revision) in engine?.unsupported.orEmpty()) {
            if (reported.add("$id:$revision")) {
                InAppLog.info("In-app campaign $id, revision $revision, is not supported by this SDK and is skipped")
            }
        }
    }

    private fun build(): InAppEngine {
        val images = deps.images
        val campaigns = synced.campaigns
        return InAppEngine(
            device = device(),
            campaigns = campaigns,
            globalCaps = synced.globalCaps,
            syncedAt = kept.state.syncedAt,
            notReady = {
                campaigns.mapNotNull { campaign ->
                    val id = campaign.takeIf { it.isJsonObject }?.asJsonObject?.let { InAppJson.string(it.get("id")) }
                    if (id == null || images.ready(campaign)) null else id
                }.toSet()
            },
            newViewId = { UUID.randomUUID().toString() },
            state = kept.state,
        )
    }

    /** Session caps follow the SDK's session, which a cold launch or 30 minutes in the background renews. */
    private fun followSession() {
        val current = deps.sessionId()
        if (current == kept.sessionId) return
        kept = kept.copy(sessionId = current)
        engine?.startSession()
        persist(force = true)
    }

    /** Only the small record is written here; the campaigns are written when a sync brings new ones. */
    private fun persist(force: Boolean = false) {
        val engine = engine ?: return
        val state = engine.state
        kept = kept.copy(state = state.copy(views = state.views.takeLast(VIEWS_KEPT)))
        if (!force && kept == saved) return
        saved = kept
        kept.save(deps.store)
    }

    // Helpers

    private val active: Boolean get() = running && deps.canRun()

    private fun now(): Long = deps.now() + synced.clockOffset

    private fun newEventId() = UUID.randomUUID().toString()

    private fun merged(vararg parts: JsonObject): JsonObject = JsonObject().also { out ->
        for (part in parts) for ((key, value) in part.entrySet()) out.add(key, value.deepCopy())
    }

    private fun explain(event: InAppEvent, decision: InAppDecision) {
        if (!settings.debug) return
        val name = event.name?.let { "${event.type.wire} $it" } ?: event.type.wire
        val skipped = decision.reasons.toSortedMap().map { "${it.key}: ${it.value.wire}" }
        val shown = decision.show?.let { "showing $it" } ?: "nothing shown"
        val detail = if (skipped.isEmpty()) "" else ". Skipped ${skipped.joinToString(", ")}"
        settings.debugOutput("In-app, $name: $shown$detail")
    }
}
