package io.grovs.inapp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.grovs.GrovsInAppListener
import io.grovs.inapp.model.InAppDefinition
import io.grovs.inapp.model.InAppDevice
import io.grovs.inapp.model.InAppPushPermission
import io.grovs.inapp.model.InAppRendered
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** The shared contract's base campaign, and the merge rule of the shared README. */
internal object InAppFixtures {
    private val cases: JsonObject by lazy {
        val text = InAppFixtures::class.java.classLoader!!.getResource("in-app-shared/cases.json")!!.readText()
        JsonParser.parseString(text).asJsonObject
    }

    fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    fun iso(millis: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(millis)

    /** Objects merge key by key; arrays and scalars, null included, replace. */
    fun merge(base: JsonElement, changes: JsonElement): JsonElement {
        if (!base.isJsonObject || !changes.isJsonObject) return changes.deepCopy()
        val out = base.asJsonObject.deepCopy()
        for ((key, value) in changes.asJsonObject.entrySet()) {
            out.add(key, if (out.has(key)) merge(out.get(key), value) else value.deepCopy())
        }
        return out
    }

    fun campaign(changes: String = "{}"): JsonObject =
        merge(cases.getAsJsonObject("base_campaign"), JsonParser.parseString(changes)).asJsonObject

    fun dummyPresentation(): InAppPresentation =
        InAppPresentation(InAppDefinition.readCampaign(campaign())!!, InAppRendered("Hello", "Welcome", emptyList(), emptyList()))
}

internal class FakeInAppStore : InAppStoring {
    val records = mutableMapOf<InAppRecord, String>()
    val written = mutableListOf<InAppRecord>()
    var refuses = false

    override fun read(record: InAppRecord): String? = records[record]

    override fun write(text: String, record: InAppRecord): Boolean {
        if (refuses) return false
        records[record] = text
        written.add(record)
        return true
    }
}

internal class FakeInAppPresenter : InAppPresenter {
    val shown = mutableListOf<InAppPresentation>()
    var dismissals = 0
    /** When set, there is nowhere to draw. */
    var refuses = false
    /** When set, [dismiss] leaves the message up, so its completion can arrive later. */
    var ignoresDismiss = false
    private var completion: ((InAppOutcome) -> Unit)? = null

    override fun present(presentation: InAppPresentation, completion: (InAppOutcome) -> Unit): Boolean {
        if (refuses) return false
        shown.add(presentation)
        this.completion = completion
        return true
    }

    override fun dismiss() {
        dismissals += 1
        if (ignoresDismiss) return
        finish(InAppOutcome.Dismissed("app"))
    }

    fun finish(outcome: InAppOutcome) {
        val done = completion
        completion = null
        done?.invoke(outcome)
    }

    val ids: List<String> get() = shown.map { it.campaign.id }
}

internal class FakeTimer(val delay: Long, val work: () -> Unit) : InAppCancellable {
    var cancelled = false
    override fun cancel() {
        cancelled = true
    }
}

internal class RecordingInAppListener : GrovsInAppListener {
    var veto: ((String) -> Boolean)? = null
    val shown = mutableListOf<String>()
    val clicks = mutableListOf<Triple<String, String?, Map<String, Any?>?>>()
    val dismissals = mutableListOf<Pair<String, String>>()
    val answers = mutableListOf<Triple<String, Any, String?>>()

    override fun shouldShowInAppMessage(campaignId: String): Boolean = veto?.invoke(campaignId) ?: true
    override fun onInAppMessageShown(campaignId: String) { shown.add(campaignId) }
    override fun onInAppMessageClicked(campaignId: String, buttonId: String?, payload: Map<String, Any?>?) {
        clicks.add(Triple(campaignId, buttonId, payload))
    }
    override fun onInAppMessageDismissed(campaignId: String, reason: String) { dismissals.add(campaignId to reason) }
    override fun onInAppMessageAnswered(campaignId: String, value: Any, comment: String?) {
        answers.add(Triple(campaignId, value, comment))
    }
}

/** The controller with every dependency faked, as `IOST/InAppControllerTests.swift:4-89`. */
internal class InAppHarness(
    val store: FakeInAppStore = FakeInAppStore(),
    imageCache: InAppImages? = null,
) {
    data class Report(val id: String, val name: String, val properties: JsonObject)

    val presenter = FakeInAppPresenter()
    val listener = RecordingInAppListener()
    val debugLines = mutableListOf<String>()
    val settings = InAppSettings().also { settings ->
        settings.listener = listener
        settings.debugOutput = { debugLines.add(it) }
    }
    /** 2026-10-01T10:00:00Z, inside the base campaign's schedule. */
    var clock = 1_790_848_800_000L
    val reports = mutableListOf<Report>()
    val syncs = mutableListOf<JsonObject>()
    val every = mutableListOf<FakeTimer>()
    /** Every timer but the fifteen minute sync. */
    val timers: List<FakeTimer> get() = every.filter { it.delay != InAppController.SYNC_EVERY_MS }
    val failures = mutableListOf<String>()
    val tracked = mutableListOf<Pair<String, JsonObject>>()
    val attributes = mutableListOf<Pair<String, JsonElement>>()
    var user: JsonObject = InAppFixtures.json("""{"first_name":"Ana"}""")
    var push = InAppPushPermission.NOT_ASKED
    var canRun = true
    var isActive = true
    var session = "s1"
    var response: Pair<Boolean, JsonObject?> = false to null
    /** When set, a sync is answered only when the test calls [heldSync]. */
    var holdSync = false
    var heldSync: (() -> Unit)? = null
    val imageFolder: File = Files.createTempDirectory("grovs-in-app-tests").toFile()
    val images: InAppImages = imageCache ?: InAppImages(imageFolder, loader = { _, done -> done(null) }, decodes = { true })

    val controller = InAppController(
        InAppController.Dependencies(
            store = store,
            presenter = presenter,
            images = images,
            device = {
                InAppDevice(
                    platform = "android", appVersion = "2.1.0", osVersion = "14", language = "en", firstSeenAt = null,
                    utcOffsetMinutes = 0, user = user, pushPermission = push,
                    unsupportedTemplates = emptyList(), unsupportedActions = emptyList(),
                )
            },
            sync = { body, done ->
                syncs.add(body)
                val answer = { done(response.first, response.second) }
                if (holdSync) heldSync = answer else answer()
            },
            report = { id, name, properties -> reports.add(Report(id, name, properties)) },
            sessionId = { session },
            canRun = { canRun },
            isActive = { isActive },
            now = { clock },
            random = { 0.5 },
            schedule = { delay, work -> FakeTimer(delay, work).also { every.add(it) } },
            sdkVersion = "4.0",
            fail = { _, message -> failures.add(message) },
            track = { name, properties -> tracked.add(name to properties) },
            setAttribute = { key, value -> attributes.add(key to value) },
        )
    ).also { it.settings = settings }

    fun iso(millis: Long): String = InAppFixtures.iso(millis)

    fun respond(campaigns: List<JsonObject>, extra: JsonObject = JsonObject()) {
        val body = JsonObject().apply {
            addProperty("campaigns_changed_at", "c1")
            addProperty("user_changed_at", "u1")
            addProperty("server_time", iso(clock))
            addProperty("first_seen_at", "2026-08-01T00:00:00Z")
            add("global_caps", InAppFixtures.json("""{"session":10,"day":10}"""))
            add("campaigns", JsonArray().also { array -> campaigns.forEach { array.add(it) } })
        }
        for ((key, value) in extra.entrySet()) body.add(key, value)
        response = true to body
    }

    fun start(campaigns: List<JsonObject>, extra: JsonObject = JsonObject()) {
        respond(campaigns, extra)
        controller.start()
    }

    fun unchanged(extra: String = "{}") {
        response = true to InAppFixtures.merge(InAppFixtures.json("""{"unchanged":true,"server_time":"${iso(clock)}"}"""),
            InAppFixtures.json(extra)).asJsonObject
    }

    fun names(): List<String> = reports.map { it.name }

    fun timer(delayMillis: Long): FakeTimer? = timers.firstOrNull { it.delay == delayMillis && !it.cancelled }

    fun kept(): InAppKept? = InAppKept.load(store)
}

internal fun testMessages(vararg campaigns: JsonObject): JsonObject =
    JsonObject().apply { add("test_messages", JsonArray().also { array -> campaigns.forEach { array.add(it) } }) }
