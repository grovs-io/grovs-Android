package io.grovs.inapp.model

import com.google.gson.JsonElement
import com.google.gson.JsonObject

internal enum class InAppTemplate(val wire: String) {
    COVER("cover"), INTERSTITIAL("interstitial"), HALF_INTERSTITIAL("half_interstitial"),
    HEADER("header"), FOOTER("footer"), ALERT("alert"), COVER_IMAGE("cover_image"),
    INTERSTITIAL_IMAGE("interstitial_image"), HALF_INTERSTITIAL_IMAGE("half_interstitial_image"), HTML("html");

    val isBanner: Boolean get() = this == HEADER || this == FOOTER
    val isImageOnly: Boolean get() = this == COVER_IMAGE || this == INTERSTITIAL_IMAGE || this == HALF_INTERSTITIAL_IMAGE

    companion object {
        fun from(wire: String?): InAppTemplate? = values().firstOrNull { it.wire == wire }
    }
}

internal enum class InAppActionType(val wire: String) {
    CLOSE("close"), OPEN_URL("open_url"), KEY_VALUE("key_value"),
    REQUEST_PUSH_PERMISSION("request_push_permission"), REQUEST_APP_RATING("request_app_rating"), OPEN_STORE("open_store");

    companion object {
        fun from(wire: String?): InAppActionType? = values().firstOrNull { it.wire == wire }
    }
}

internal data class InAppAction(val type: InAppActionType, val url: String?, val payload: JsonObject?)

internal data class InAppButton(val id: String, val text: String, val action: InAppAction)

internal enum class InAppQuestionType(val wire: String) {
    STARS("stars"), CHOICE("choice");

    companion object {
        fun from(wire: String?): InAppQuestionType? = values().firstOrNull { it.wire == wire }
    }
}

internal data class InAppOption(val id: String, val text: String)

internal data class InAppComment(val split: Int?, val low: String?, val high: String?)

/** The number of stars, or the id of the option chosen. */
internal sealed class InAppAnswer {
    data class Stars(val number: Int) : InAppAnswer()
    data class Option(val id: String) : InAppAnswer()

    /** Int for stars, the option id for a choice: as reported and handed to the host. */
    val value: Any
        get() = when (this) {
            is Stars -> number
            is Option -> id
        }
}

internal data class InAppQuestion(
    val id: String,
    val type: InAppQuestionType,
    val options: List<InAppOption>,
    val comment: InAppComment?,
    val thanks: String?,
    val reviewFrom: Int?,
) {
    /** What to ask after this answer; null when nothing is asked. */
    fun prompt(answer: InAppAnswer): String? {
        val comment = comment ?: return null
        val split = comment.split
        val text = if (answer is InAppAnswer.Stars && split != null && answer.number > split) comment.high else comment.low
        return text?.takeIf { it.isNotEmpty() }
    }
}

internal enum class InAppLayout(val wire: String) {
    FULL("full"), MODAL("modal"), BANNER_TOP("banner_top"), BANNER_BOTTOM("banner_bottom");

    val isBanner: Boolean get() = this == BANNER_TOP || this == BANNER_BOTTOM

    companion object {
        fun from(wire: String?): InAppLayout? = values().firstOrNull { it.wire == wire }
    }
}

/** The `html` template's own page, drawn by a web view. */
internal data class InAppPage(val html: String, val layout: InAppLayout)

/** `content.orientation`; a device reports only portrait or landscape. */
internal enum class InAppOrientation(val wire: String) {
    ANY("any"), PORTRAIT("portrait"), LANDSCAPE("landscape");

    /** Whether a message restricted to this orientation may be on screen while the device is held `device`. */
    fun fits(device: InAppOrientation): Boolean = this == ANY || this == device

    companion object {
        fun from(wire: String?): InAppOrientation? = values().firstOrNull { it.wire == wire }
    }
}

internal data class InAppContent(
    val title: String?,
    val message: String?,
    val buttons: List<InAppButton>,
    val tapAction: InAppAction?,
    /** False when only a button, the tap action or an answer may close the message. */
    val dismissible: Boolean = true,
    val question: InAppQuestion? = null,
    val page: InAppPage? = null,
    val orientation: InAppOrientation = InAppOrientation.ANY,
) {
    /** Buttons first, then the tap action: the order links are listed in. */
    val actions: List<InAppAction> get() = buttons.map { it.action } + listOfNotNull(tapAction)
}

internal data class InAppFilter(val field: String, val op: String, val value: JsonElement)

internal sealed class InAppTrigger {
    object AppOpen : InAppTrigger()
    data class ScreenView(val name: String?) : InAppTrigger()
    data class Event(val name: String, val filters: List<InAppFilter>) : InAppTrigger()
}

internal sealed class InAppCondition {
    data class Platform(val op: String, val value: String) : InAppCondition()
    data class Language(val op: String, val value: String) : InAppCondition()
    data class AppVersion(val op: String, val value: JsonElement) : InAppCondition()
    data class OsVersion(val op: String, val value: JsonElement) : InAppCondition()
    data class EventCount(val name: String, val op: String, val value: JsonElement) : InAppCondition()
    data class UserAttribute(val key: String, val op: String, val value: JsonElement) : InAppCondition()
    data class FirstSeenBefore(val at: Long) : InAppCondition()
    data class FirstSeenAfter(val at: Long) : InAppCondition()
    data class FirstSeenWithinDays(val days: Long) : InAppCondition()
}

internal data class InAppCaps(val session: Int?, val day: Int?, val week: Int?, val lifetime: Int?, val ignoreGlobal: Boolean)

/** Times are epoch milliseconds; days are 1 = Monday to 7 = Sunday; from and to are "HH:MM" local. */
internal data class InAppSchedule(val startAt: Long, val endAt: Long?, val days: List<Int>?, val from: String?, val to: String?)

/** A definition the engine has read and found whole. Times are epoch milliseconds. */
internal data class InAppCampaign(
    val format: Int,
    val id: String,
    val createdAt: Long,
    val priority: Int,
    val template: InAppTemplate,
    val content: InAppContent,
    val triggers: List<InAppTrigger>,
    val conditions: List<InAppCondition>,
    val caps: InAppCaps,
    val schedule: InAppSchedule,
    val delaySeconds: Int,
    val deliveryMode: String,
    /** True when variants, control_group or conversion is set: not for a format 1 engine. */
    val reserved: Boolean,
    /** As received: colours, images, revision, backdrop, close_button. */
    val raw: JsonObject,
) {
    val revision: Int get() = InAppJson.whole(raw.get("revision")) ?: 1
}

internal enum class InAppPushPermission(val wire: String) {
    GRANTED("granted"), DENIED("denied"), NOT_ASKED("not_asked");

    companion object {
        fun from(wire: String?): InAppPushPermission? = values().firstOrNull { it.wire == wire }
    }
}

internal data class InAppDevice(
    val platform: String,
    val appVersion: String,
    val osVersion: String,
    val language: String,
    val firstSeenAt: Long?,
    val utcOffsetMinutes: Int,
    val user: JsonObject,
    val pushPermission: InAppPushPermission,
    val unsupportedTemplates: List<String>,
    val unsupportedActions: List<String>,
    val orientation: InAppOrientation = InAppOrientation.PORTRAIT,
)

internal enum class InAppEventType(val wire: String) {
    APP_OPEN("app_open"), SCREEN_VIEW("screen_view"), EVENT("event");

    companion object {
        fun from(wire: String?): InAppEventType? = values().firstOrNull { it.wire == wire }
    }
}

internal data class InAppEvent(val type: InAppEventType, val name: String? = null, val properties: JsonObject = JsonObject())

internal enum class InAppReason(val wire: String) {
    STALE("stale"), UNSUPPORTED("unsupported"), NO_TRIGGER("no_trigger"), NOT_READY("not_ready"),
    SCHEDULE("schedule"), CONDITION("condition"), ANSWERED("answered"), CAP_SESSION("cap_session"),
    CAP_DAY("cap_day"), CAP_WEEK("cap_week"), CAP_LIFETIME("cap_lifetime"),
    CAP_GLOBAL_SESSION("cap_global_session"), CAP_GLOBAL_DAY("cap_global_day"),
    PERSONALIZATION("personalization"), UNSAFE_LINK("unsafe_link"), LOWER_PRIORITY("lower_priority"),
}

internal data class InAppRendered(val title: String?, val message: String?, val buttons: List<String>, val urls: List<String>)

internal data class InAppDecision(
    val show: String?,
    val view: String?,
    val reasons: Map<String, InAppReason>,
    val rendered: InAppRendered?,
    /** Seconds the display waits before it decides again and shows the campaign. */
    val delay: Int? = null,
)

internal data class InAppGlobalCaps(val session: Int?, val day: Int?)
