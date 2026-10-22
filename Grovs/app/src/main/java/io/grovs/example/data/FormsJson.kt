package io.grovs.example.data

import io.grovs.model.events.PaymentEventType
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private inline fun <T> parseOrNull(json: String?, block: (String) -> T): T? =
    if (json == null) null else try {
        block(json)
    } catch (e: JSONException) {
        null
    }

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map(::getString)

private inline fun <reified E : Enum<E>> JSONObject.enumOr(key: String, default: E): E =
    enumValues<E>().firstOrNull { it.name == optString(key) } ?: default

private fun JSONObject.keyValuesOr(key: String, default: List<KeyValue>): List<KeyValue> =
    if (has(key)) keyValuesFrom(optJSONArray(key)) else default

private fun Redirect.toJson() = JSONObject().put("url", url).put("open", openAppIfInstalled)

private fun JSONObject.redirectOr(key: String, default: Redirect): Redirect {
    val o = optJSONObject(key) ?: return default
    return Redirect(o.optString("url", default.url), o.optBoolean("open", default.openAppIfInstalled))
}

fun encodeStrings(list: List<String>): String = JSONArray(list).toString()

fun decodeStrings(json: String?): List<String> = parseOrNull(json) { JSONArray(it).strings() } ?: emptyList()

fun encodeKeyValues(list: List<KeyValue>): String = list.toJsonArray().toString()

fun decodeKeyValues(json: String?): List<KeyValue>? = parseOrNull(json) { keyValuesFrom(JSONArray(it)) }

fun encodeLinkForm(f: LinkForm): String = JSONObject()
    .put("title", f.title)
    .put("subtitle", f.subtitle)
    .put("data", f.data.toJsonArray())
    .put("imageUrl", f.imageUrl)
    .put("tags", JSONArray(f.tags))
    .put("utmOn", f.utmOn)
    .put("utmSource", f.utmSource)
    .put("utmMedium", f.utmMedium)
    .put("utmCampaign", f.utmCampaign)
    .put("redirectsOn", f.redirectsOn)
    .put("android", f.android.toJson())
    .put("ios", f.ios.toJson())
    .put("desktop", f.desktop.toJson())
    .put("previewAndroid", f.previewAndroid.name)
    .put("previewIos", f.previewIos.name)
    .put("clipboardAndroid", f.clipboardAndroid.name)
    .put("clipboardIos", f.clipboardIos.name)
    .toString()

fun decodeLinkForm(json: String?): LinkForm = parseOrNull(json) { s ->
    val o = JSONObject(s)
    val d = LinkForm()
    LinkForm(
        title = o.optString("title", d.title),
        subtitle = o.optString("subtitle", d.subtitle),
        data = o.keyValuesOr("data", d.data),
        imageUrl = o.optString("imageUrl", d.imageUrl),
        tags = o.optJSONArray("tags").strings(),
        utmOn = o.optBoolean("utmOn", d.utmOn),
        utmSource = o.optString("utmSource", d.utmSource),
        utmMedium = o.optString("utmMedium", d.utmMedium),
        utmCampaign = o.optString("utmCampaign", d.utmCampaign),
        redirectsOn = o.optBoolean("redirectsOn", d.redirectsOn),
        android = o.redirectOr("android", d.android),
        ios = o.redirectOr("ios", d.ios),
        desktop = o.redirectOr("desktop", d.desktop),
        previewAndroid = o.enumOr("previewAndroid", d.previewAndroid),
        previewIos = o.enumOr("previewIos", d.previewIos),
        clipboardAndroid = o.enumOr("clipboardAndroid", d.clipboardAndroid),
        clipboardIos = o.enumOr("clipboardIos", d.clipboardIos),
    )
} ?: LinkForm()

fun encodeEventForm(f: EventForm): String = JSONObject()
    .put("name", f.name).put("properties", f.properties.toJsonArray()).put("tags", JSONArray(f.tags)).toString()

fun decodeEventForm(json: String?): EventForm = parseOrNull(json) { s ->
    val o = JSONObject(s)
    val d = EventForm()
    EventForm(o.optString("name", d.name), o.keyValuesOr("properties", d.properties), o.optJSONArray("tags").strings())
} ?: EventForm()

fun encodeScreenForm(f: ScreenForm): String = JSONObject()
    .put("name", f.name).put("properties", f.properties.toJsonArray()).toString()

fun decodeScreenForm(json: String?): ScreenForm = parseOrNull(json) { s ->
    val o = JSONObject(s)
    val d = ScreenForm()
    ScreenForm(o.optString("name", d.name), o.keyValuesOr("properties", d.properties))
} ?: ScreenForm()

fun encodePurchaseForm(f: PurchaseForm): String = JSONObject()
    .put("type", f.type.name)
    .put("amount", f.amount)
    .put("currency", f.currency)
    .put("productId", f.productId)
    .put("startDaysAgo", f.startDaysAgo)
    .put("playJson", f.playJson)
    .toString()

fun decodePurchaseForm(json: String?): PurchaseForm = parseOrNull(json) { s ->
    val o = JSONObject(s)
    val d = PurchaseForm()
    PurchaseForm(
        type = o.enumOr<PaymentEventType>("type", d.type),
        amount = o.optString("amount", d.amount),
        currency = o.optString("currency", d.currency),
        productId = o.optString("productId", d.productId),
        startDaysAgo = o.optInt("startDaysAgo", d.startDaysAgo),
        playJson = o.optString("playJson", d.playJson),
    )
} ?: PurchaseForm()

fun encodeIdentity(identity: Identity): String = JSONObject()
    .put("identifier", identity.identifier ?: JSONObject.NULL)
    .put("attributes", identity.attributes.toJsonArray())
    .toString()

fun decodeIdentity(json: String?, fallback: Identity): Identity = parseOrNull(json) { s ->
    val o = JSONObject(s)
    Identity(
        identifier = if (o.isNull("identifier")) null else o.getString("identifier"),
        attributes = o.keyValuesOr("attributes", fallback.attributes),
    )
} ?: fallback
