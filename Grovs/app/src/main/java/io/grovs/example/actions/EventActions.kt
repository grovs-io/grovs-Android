package io.grovs.example.actions

import io.grovs.Grovs
import io.grovs.example.data.EventForm
import io.grovs.example.data.KeyValue
import io.grovs.example.data.PurchaseForm
import io.grovs.example.data.ScreenForm
import io.grovs.example.data.parseAmountToCents
import io.grovs.example.data.toValueMap
import io.grovs.utils.InstantCompat
import java.util.Date

private const val DAY_MS = 86_400_000L

object EventActions {

    fun track(form: EventForm, expectRejected: Boolean = false): TestAction {
        val name = form.name.trim()
        val properties = form.properties.toValueMap().ifEmpty { null }
        val tags = form.tags.ifEmpty { null }
        val code = callCode("track", "name" to name, "properties" to properties, "tags" to tags)
        return TestAction(LogType.EVENT, "track $name", code) {
            Grovs.track(name, properties, tags)
            if (expectRejected) ActionResult(LogState.WARNING, "sent anyway, the SDK should reject it")
            else ActionResult(LogState.OK, tags?.let { "tags ${it.joinToString()}" } ?: "sent")
        }
    }

    fun screen(form: ScreenForm): TestAction {
        val name = form.name.trim()
        val properties = form.properties.toValueMap().ifEmpty { null }
        val code = callCode("trackScreenView", "screenName" to name, "properties" to properties)
        return TestAction(LogType.SCREEN, "Screen $name", code) {
            Grovs.trackScreenView(name, properties)
            ActionResult(LogState.OK, "sent")
        }
    }

    fun purchase(form: PurchaseForm, nowMillis: Long): TestAction? {
        val cents = parseAmountToCents(form.amount) ?: return null
        val start = nowMillis - form.startDaysAgo * DAY_MS
        val code = callCode(
            "logCustomPurchase",
            "type" to form.type.name,
            "priceInCents" to cents,
            "currency" to form.currency,
            "productId" to form.productId,
            "startDate" to Date(start),
        )
        return TestAction(LogType.PURCHASE, form.label(), code) {
            Grovs.logCustomPurchase(form.type, cents, form.currency, form.productId, InstantCompat(start))
            ActionResult(LogState.OK, "$cents cents · ${form.productId}")
        }
    }

    fun playPurchase(json: String): TestAction =
        TestAction(LogType.PURCHASE, "Google Play purchase", callCode("logInAppPurchase", "originalJson" to json)) {
            Grovs.logInAppPurchase(json)
            ActionResult(LogState.OK, "sent")
        }

    fun globalTags(tags: List<String>): TestAction =
        TestAction(LogType.SDK, "Global tags set", callCode("setGlobalTags", "tags" to tags.ifEmpty { null })) {
            Grovs.setGlobalTags(tags.ifEmpty { null })
            ActionResult(LogState.OK, tags.joinToString().ifEmpty { "cleared" })
        }

    fun aliases(rows: List<KeyValue>): TestAction {
        val map = rows.filter { it.key.isNotBlank() }.associate { it.key.trim() to it.raw }
        return TestAction(LogType.SDK, "Screen aliases set", callCode("setScreenAliases", "aliases" to map)) {
            Grovs.setScreenAliases(map)
            ActionResult(LogState.OK, "${map.size} aliases")
        }
    }

    fun trigger(name: String): TestAction {
        val trimmed = name.trim()
        return TestAction(LogType.EVENT, "Trigger $trimmed", callCode("track", "name" to trimmed)) {
            Grovs.track(trimmed)
            ActionResult(LogState.OK, "sent")
        }
    }
}
