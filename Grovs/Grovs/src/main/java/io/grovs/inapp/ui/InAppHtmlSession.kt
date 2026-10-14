package io.grovs.inapp.ui

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.grovs.inapp.InAppBridge
import io.grovs.inapp.InAppCancellable
import io.grovs.inapp.InAppOutcome
import io.grovs.inapp.InAppPresentation
import io.grovs.inapp.model.InAppAction
import io.grovs.inapp.model.InAppActionType
import io.grovs.inapp.model.InAppJson
import io.grovs.inapp.model.InAppLayout
import io.grovs.inapp.model.InAppLinkRule
import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel
import java.util.UUID

/** One show of an `html` message: what its page may ask, when it is revealed, and how it ends. Main thread only. */
internal class InAppHtmlSession(
    private val presentation: InAppPresentation,
    private val schedule: (delayMillis: Long, work: () -> Unit) -> InAppCancellable,
    private val requestPush: (done: (String) -> Unit) -> Unit,
    private val evaluate: (script: String) -> Unit,
    private val onHeight: (dp: Double) -> Unit,
    private val onReveal: () -> Unit,
    private val finish: (InAppOutcome) -> Unit,
    /** Proves a message came from the page this show injected. */
    val token: String = UUID.randomUUID().toString(),
) {
    companion object {
        const val DEADLINE_MILLIS = 5_000L
        const val NAME_LIMIT = 100

        fun isRemote(scheme: String?): Boolean = scheme?.lowercase() == "https" || scheme?.lowercase() == "http"

        private val elements = Gson().getAdapter(JsonElement::class.java)

        /** Strict JSON only: a raw NaN or other non-JSON text is refused. */
        private fun parse(raw: String): JsonElement? = try {
            elements.fromJson(raw)
        } catch (e: Exception) {
            null
        }
    }

    private val campaignId = presentation.campaign.id
    private val layout = presentation.campaign.content.page?.layout ?: InAppLayout.FULL
    private var deadline: InAppCancellable? = null
    private var ready = false
    private var contentHeight = 0.0
    private var pageStarts = 0

    var isShown = false
        private set
    var isFinished = false
        private set

    fun start() {
        deadline = schedule(DEADLINE_MILLIS) {
            if (!isShown) {
                DebugLogger.instance.log(LogLevel.ERROR, "In-app message $campaignId was not ready within 5 seconds")
                complete(InAppOutcome.Unshown("timeout"))
            }
        }
    }

    /** Anything not from the main frame, without this show's token, of another version or an unknown type is ignored. */
    fun receive(raw: String, mainFrame: Boolean) {
        if (!mainFrame || isFinished) return
        val message = parse(raw) as? JsonObject ?: return
        if (InAppJson.whole(message.get("v")) != 1) return
        if (InAppJson.string(message.get("token")) != token) return
        val id = InAppJson.string(message.get("id")) ?: return
        val type = InAppJson.string(message.get("type")) ?: return
        val args = message.get("args") as? JsonObject ?: return
        when (type) {
            "ready" -> {
                val missing = InAppJson.bool(args.get("missing")) ?: return
                if (isShown) return
                if (missing) return complete(InAppOutcome.Unshown("missing"))
                ready = true
                showIfReady()
            }
            "height" -> {
                val value = InAppJson.double(args.get("value")) ?: return
                if (value < 0 || value.isNaN() || value.isInfinite()) return
                contentHeight = value
                onHeight(value)
                showIfReady()
            }
            "requestPushPermission" -> {
                if (!isShown) return reply(id, error = "not_shown")
                requestPush { state -> reply(id, result = state) }
            }
            // Not queued: before the message is shown a call that does not answer is dropped.
            else -> if (isShown) call(type, args)
        }
    }

    /** Before it showed nobody saw it, so it is not a dismissal. */
    fun leave(reason: String) {
        complete(if (isShown) InAppOutcome.Dismissed(reason) else InAppOutcome.Unshown(reason))
    }

    /** Ends without an outcome: the presenter could not draw it, so nobody is told. */
    fun abandon() {
        isFinished = true
        deadline?.cancel()
    }

    /** True when the WebView must not follow this navigation. A tapped main-frame link the bridge missed is opened. */
    fun blocksNavigation(mainFrame: Boolean, gesture: Boolean, url: String): Boolean {
        if (!mainFrame) return false
        if (gesture && isShown && !isFinished) open(JsonObject().apply { addProperty("url", url) })
        return true
    }

    /** True for every main-frame page start after the page's own load, such as a POST or a reload. */
    fun blocksPageStart(): Boolean = pageStarts++ > 0

    private fun showIfReady() {
        if (isShown || isFinished || !ready) return
        if (layout != InAppLayout.FULL && contentHeight <= 0) return
        // The app may have paused in-app or moved to an excluded screen while the page loaded.
        if (presentation.canShow?.invoke() == false) {
            DebugLogger.instance.log(LogLevel.INFO, "In-app message $campaignId was ready but can no longer show")
            return complete(InAppOutcome.Unshown("app"))
        }
        isShown = true
        deadline?.cancel()
        onReveal()
        presentation.onShown?.invoke()
    }

    private fun call(type: String, args: JsonObject) {
        when (type) {
            "close" -> leave("close_button")
            "track" -> track(args)
            "open" -> open(args)
            "setAttribute" -> setAttribute(args)
        }
    }

    private fun track(args: JsonObject) {
        val name = InAppJson.string(args.get("name")) ?: return
        if (name.length !in 1..NAME_LIMIT || name in InAppBridge.RESERVED_EVENTS) return
        val given = args.get("properties")
        val properties = when {
            InAppJson.isNull(given) -> JsonObject()
            given is JsonObject -> given
            else -> return
        }
        presentation.onTrack?.invoke(name, properties)
    }

    private fun open(args: JsonObject) {
        val raw = InAppJson.string(args.get("url")) ?: return
        val link = InAppLinkRule.allowed(raw) ?: return
        // A fragment has nothing to open; the bridge scrolls to it in the page.
        if (link.startsWith("#")) return
        val target = InAppHtmlDocument.openable(link)
        if (target == null) {
            DebugLogger.instance.log(LogLevel.INFO, "In-app message $campaignId opened a link that is not a URL")
            return
        }
        if (InAppJson.bool(args.get("active")) == false) {
            DebugLogger.instance.log(LogLevel.INFO, "In-app message $campaignId opened a link without a tap")
            return
        }
        complete(InAppOutcome.Clicked(buttonId = null, action = InAppAction(InAppActionType.OPEN_URL, target, null)))
    }

    private fun setAttribute(args: JsonObject) {
        val key = InAppJson.string(args.get("key")) ?: return
        if (key.length !in 1..NAME_LIMIT || !args.has("value")) return
        val value: JsonElement = args.get("value")
        val scalar = value.isJsonNull || (value is JsonPrimitive &&
            (value.isString || value.isBoolean || (value.isNumber && value.asDouble.let { !it.isNaN() && !it.isInfinite() })))
        if (!scalar) return
        presentation.onAttribute?.invoke(key, value)
    }

    private fun reply(id: String, result: String? = null, error: String? = null) {
        if (isFinished) return
        val message = JsonObject()
        message.addProperty("v", 1)
        message.addProperty("id", id)
        message.addProperty("ok", error == null)
        if (error != null) message.addProperty("error", error) else if (result != null) message.addProperty("result", result)
        evaluate("window.__grovsReply(" + InAppHtmlDocument.scriptJson(message) + ")")
    }

    /** Reports once; after it every late callback from the page is ignored. */
    private fun complete(outcome: InAppOutcome) {
        if (isFinished) return
        isFinished = true
        deadline?.cancel()
        finish(outcome)
    }
}
