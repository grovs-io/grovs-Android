package io.grovs.inapp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.grovs.inapp.model.InAppActionType
import io.grovs.inapp.model.InAppCampaign
import io.grovs.inapp.model.InAppCondition
import io.grovs.inapp.model.InAppDecision
import io.grovs.inapp.model.InAppDefinition
import io.grovs.inapp.model.InAppDevice
import io.grovs.inapp.model.InAppEvent
import io.grovs.inapp.model.InAppEventType
import io.grovs.inapp.model.InAppGlobalCaps
import io.grovs.inapp.model.InAppJson
import io.grovs.inapp.model.InAppPersonalize
import io.grovs.inapp.model.InAppPushPermission
import io.grovs.inapp.model.InAppReason
import io.grovs.inapp.model.InAppRendered
import io.grovs.inapp.model.InAppTime
import io.grovs.inapp.model.InAppTrigger
import io.grovs.inapp.model.InAppValues

internal data class InAppView(
    val id: String,
    val campaign: String,
    val at: Long,
    /** False for a campaign that ignores the global caps. */
    val global: Boolean,
)

internal data class InAppEngineState(
    val views: List<InAppView> = emptyList(),
    /** Ids of the views recorded in the current session. */
    val session: List<String> = emptyList(),
    val counts: Map<String, Int> = emptyMap(),
    val syncedAt: Long = 0,
    /** Ids of the campaigns whose question was answered on this device. */
    val answered: List<String> = emptyList(),
) {
    fun toJson(): JsonObject = JsonObject().apply {
        add("views", JsonArray().also { out ->
            for (view in views) {
                out.add(JsonObject().apply {
                    addProperty("id", view.id)
                    addProperty("campaign", view.campaign)
                    addProperty("at", view.at)
                    addProperty("global", view.global)
                })
            }
        })
        add("session", JsonArray().also { out -> session.forEach { out.add(it) } })
        add("counts", JsonObject().also { out -> counts.forEach { (name, count) -> out.addProperty(name, count) } })
        addProperty("syncedAt", syncedAt)
        add("answered", JsonArray().also { out -> answered.forEach { out.add(it) } })
    }

    companion object {
        /** Null when unreadable; a damaged `answered` loses only the answers, not the views the caps count. */
        fun fromJson(json: JsonElement?): InAppEngineState? {
            val raw = json?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val views = strictList(raw.get("views"), ::readView) ?: return null
            val session = strictList(raw.get("session"), InAppJson::string) ?: return null
            val countsRaw = raw.get("counts")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val counts = LinkedHashMap<String, Int>()
            for ((name, count) in countsRaw.entrySet()) counts[name] = InAppJson.whole(count) ?: return null
            val syncedAt = InAppJson.double(raw.get("syncedAt"))?.toLong() ?: return null
            val answered = strictList(raw.get("answered"), InAppJson::string) ?: emptyList()
            return InAppEngineState(views, session, counts, syncedAt, answered)
        }

        private fun readView(value: JsonElement): InAppView? {
            val raw = value.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            return InAppView(
                InAppJson.string(raw.get("id")) ?: return null,
                InAppJson.string(raw.get("campaign")) ?: return null,
                InAppJson.double(raw.get("at"))?.toLong() ?: return null,
                InAppJson.bool(raw.get("global")) ?: return null,
            )
        }

        private fun <T> strictList(value: JsonElement?, read: (JsonElement) -> T?): List<T>? {
            val items = value?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
            return items.map { read(it) ?: return null }
        }
    }
}

/** Decides which campaign an event shows. Pure logic; the order of checks is part of the contract. */
internal class InAppEngine(
    device: InAppDevice,
    campaigns: List<JsonElement>,
    private val globalCaps: InAppGlobalCaps,
    syncedAt: Long,
    private val notReady: () -> Set<String> = { emptySet() },
    private val newViewId: () -> String,
    state: InAppEngineState? = null,
) {
    private class Entry(val id: String, val campaign: InAppCampaign?, val revision: Int)

    private var device: InAppDevice = device
    private val entries: List<Entry> = campaigns.mapNotNull { raw ->
        val obj = raw.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        Entry(InAppJson.string(obj.get("id")) ?: "", InAppDefinition.readCampaign(obj), InAppJson.whole(obj.get("revision")) ?: 1)
    }
    private val counted: Set<String> = entries.mapNotNull { it.campaign }.filter(::supported)
        .flatMap { campaign -> campaign.conditions.filterIsInstance<InAppCondition.EventCount>().map { it.name } }.toSet()

    var state: InAppEngineState = state ?: InAppEngineState(syncedAt = syncedAt)
        private set

    /** The user and the permissions change between syncs; which campaigns are supported does not. */
    fun update(device: InAppDevice) {
        this.device = device
    }

    /** Campaigns this device cannot read or cannot show, as (id, revision). */
    val unsupported: List<Pair<String, Int>>
        get() = entries.filter { entry -> entry.campaign?.let { !supported(it) } ?: true }.map { it.id to it.revision }

    fun startSession() {
        state = state.copy(session = emptyList())
    }

    /** A sync restarts the 48 hour clock and changes nothing else. */
    fun markSynced(at: Long) {
        state = state.copy(syncedAt = at)
    }

    /** An answered question is not asked again, in this session or a later one. */
    fun answer(id: String) {
        if (id !in state.answered) state = state.copy(answered = state.answered + id)
    }

    /** Counts the event, decides, and records the view: one call, as the shared cases expect. */
    fun track(event: InAppEvent, at: Long): InAppDecision {
        val decision = peek(event, at)
        val shown = decision.show ?: return decision
        return decision.copy(view = record(shown, at))
    }

    /** Counts the event and decides, recording nothing: the caller may still be vetoed or busy. */
    fun peek(event: InAppEvent, at: Long): InAppDecision {
        val name = event.name
        if (event.type == InAppEventType.EVENT && name != null && name in counted) {
            val count = (state.counts[name] ?: 0) + 1
            state = state.copy(counts = state.counts + (name to count))
        }
        return recheck(event, at)
    }

    /** Decides again for an event already counted, as a message that waited must. */
    fun recheck(event: InAppEvent, at: Long): InAppDecision {
        val reasons = LinkedHashMap<String, InAppReason>()
        val passing = ArrayList<Pair<InAppCampaign, InAppRendered>>()
        for (entry in entries) {
            val campaign = entry.campaign
            if (campaign == null) {
                reasons[entry.id] = InAppReason.UNSUPPORTED
                continue
            }
            val reason = skipReason(campaign, event, at)
            if (reason != null) {
                reasons[entry.id] = reason
                continue
            }
            when (val outcome = InAppPersonalize.render(campaign, event, device.user)) {
                is InAppPersonalize.Outcome.Skipped -> reasons[entry.id] = outcome.reason
                is InAppPersonalize.Outcome.Rendered -> passing.add(campaign to outcome.rendered)
            }
        }
        passing.sortWith { a, b -> order(a.first, b.first) }
        val winner = passing.firstOrNull() ?: return InAppDecision(null, null, reasons, null)
        for (loser in passing.drop(1)) reasons[loser.first.id] = InAppReason.LOWER_PRIORITY
        return InAppDecision(winner.first.id, null, reasons, winner.second, winner.first.delaySeconds)
    }

    /** Records a view of a campaign about to be drawn; null for an unknown campaign. */
    fun record(id: String, at: Long): String? {
        val campaign = campaign(id) ?: return null
        val view = InAppView(newViewId(), id, at, !campaign.caps.ignoreGlobal)
        state = state.copy(views = state.views + view, session = state.session + view.id)
        return view.id
    }

    fun campaign(id: String): InAppCampaign? = entries.firstOrNull { it.campaign?.id == id }?.campaign

    private fun order(a: InAppCampaign, b: InAppCampaign): Int {
        if (a.priority != b.priority) return b.priority.compareTo(a.priority)
        if (a.createdAt != b.createdAt) return a.createdAt.compareTo(b.createdAt)
        return compareUtf8(a.id, b.id)
    }

    /** Byte order as unsigned values, the way Swift and JavaScript compare UTF-8. */
    private fun compareUtf8(a: String, b: String): Int {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        for (index in 0 until minOf(x.size, y.size)) {
            val difference = (x[index].toInt() and 0xFF) - (y[index].toInt() and 0xFF)
            if (difference != 0) return difference
        }
        return x.size - y.size
    }

    private fun skipReason(campaign: InAppCampaign, event: InAppEvent, at: Long): InAppReason? {
        if (at - state.syncedAt > STALE_AFTER) return InAppReason.STALE
        if (!supported(campaign)) return InAppReason.UNSUPPORTED
        if (campaign.triggers.none { matches(it, event) }) return InAppReason.NO_TRIGGER
        if (campaign.id in notReady()) return InAppReason.NOT_READY
        if (!InAppTime.inSchedule(campaign.schedule, at, device.utcOffsetMinutes)) return InAppReason.SCHEDULE
        if (!conditionsPass(campaign, at)) return InAppReason.CONDITION
        if (campaign.id in state.answered) return InAppReason.ANSWERED
        return capReason(campaign, at)
    }

    private fun supported(campaign: InAppCampaign): Boolean {
        if (campaign.format != 1 || campaign.deliveryMode != "device" || campaign.reserved) return false
        if (campaign.template.wire in device.unsupportedTemplates) return false
        return campaign.content.actions.none { it.type.wire in device.unsupportedActions }
    }

    private fun matches(trigger: InAppTrigger, event: InAppEvent): Boolean = when (trigger) {
        InAppTrigger.AppOpen -> event.type == InAppEventType.APP_OPEN
        is InAppTrigger.ScreenView -> event.type == InAppEventType.SCREEN_VIEW &&
            (trigger.name == null || trigger.name.lowercase() == event.name.orEmpty().lowercase())
        is InAppTrigger.Event -> event.type == InAppEventType.EVENT && trigger.name == event.name &&
            trigger.filters.all { InAppValues.compare(event.properties.get(it.field), it.op, it.value) }
    }

    private fun conditionsPass(campaign: InAppCampaign, at: Long): Boolean {
        val asksForPush = campaign.content.actions.any { it.type == InAppActionType.REQUEST_PUSH_PERMISSION }
        if (asksForPush && device.pushPermission == InAppPushPermission.GRANTED) return false
        if (!campaign.content.orientation.fits(device.orientation)) return false
        return campaign.conditions.all { conditionPasses(it, at) }
    }

    private fun conditionPasses(condition: InAppCondition, at: Long): Boolean = when (condition) {
        is InAppCondition.Platform -> InAppValues.compare(JsonPrimitive(device.platform), condition.op, JsonPrimitive(condition.value))
        is InAppCondition.Language -> InAppValues.compare(JsonPrimitive(device.language), condition.op, JsonPrimitive(condition.value))
        is InAppCondition.AppVersion -> InAppValues.compareVersions(device.appVersion, condition.op, condition.value)
        is InAppCondition.OsVersion -> InAppValues.compareVersions(device.osVersion, condition.op, condition.value)
        is InAppCondition.UserAttribute -> InAppValues.compare(device.user.get(condition.key), condition.op, condition.value)
        is InAppCondition.EventCount -> InAppValues.compare(JsonPrimitive(state.counts[condition.name] ?: 0), condition.op, condition.value)
        is InAppCondition.FirstSeenBefore -> device.firstSeenAt?.let { it < condition.at } ?: false
        is InAppCondition.FirstSeenAfter -> device.firstSeenAt?.let { it > condition.at } ?: false
        is InAppCondition.FirstSeenWithinDays -> device.firstSeenAt?.let { at - it <= condition.days * DAY } ?: false
    }

    private fun capReason(campaign: InAppCampaign, at: Long): InAppReason? {
        val caps = campaign.caps
        val mine = state.views.filter { it.campaign == campaign.id }
        val sessionIds = state.session.toHashSet()
        val inSession = state.views.filter { it.id in sessionIds }
        val today = day(at)
        val thisWeek = week(at)

        if (over(caps.session, inSession.count { it.campaign == campaign.id })) return InAppReason.CAP_SESSION
        if (over(caps.day, mine.count { day(it.at) == today })) return InAppReason.CAP_DAY
        if (over(caps.week, mine.count { week(it.at) == thisWeek })) return InAppReason.CAP_WEEK
        if (over(caps.lifetime, mine.size)) return InAppReason.CAP_LIFETIME
        if (caps.ignoreGlobal) return null
        if (over(globalCaps.session, inSession.count { it.global })) return InAppReason.CAP_GLOBAL_SESSION
        return if (over(globalCaps.day, state.views.count { it.global && day(it.at) == today })) InAppReason.CAP_GLOBAL_DAY else null
    }

    private fun over(limit: Int?, count: Int): Boolean = limit != null && count >= limit

    private fun day(at: Long): String = InAppTime.dayOf(at, device.utcOffsetMinutes)

    private fun week(at: Long): String = InAppTime.weekOf(at, device.utcOffsetMinutes)

    private companion object {
        const val DAY = 86_400_000L
        const val STALE_AFTER = 48 * 3_600_000L
    }
}
