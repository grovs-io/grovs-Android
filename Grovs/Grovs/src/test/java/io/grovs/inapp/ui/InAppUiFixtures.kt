package io.grovs.inapp.ui

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.grovs.inapp.InAppImages
import io.grovs.inapp.InAppPresentation
import io.grovs.inapp.model.InAppCampaign
import io.grovs.inapp.model.InAppDefinition
import io.grovs.inapp.model.InAppRendered
import java.io.File

internal object InAppUiFixtures {
    const val PORTRAIT = "https://cdn.example/p.png"
    const val LANDSCAPE = "https://cdn.example/l.png"

    /** `base_campaign` from the shared cases.json. */
    private const val BASE = """{"format":1,"id":"c1","revision":1,"created_at":"2026-09-01T00:00:00Z","priority":1,
        "template":"interstitial","content":{"title":"Hello","message":"Welcome","image":{"portrait":null,"landscape":null},
        "colors":{},"close_button":true,"backdrop":"dim","buttons":[],"tap_action":null},
        "triggers":[{"type":"event","name":"cart_viewed"}],"conditions":[],
        "caps":{"session":null,"day":null,"week":null,"lifetime":null,"ignore_global":false},
        "schedule":{"start_at":"2026-09-01T00:00:00Z","end_at":null,"days":null,"from":null,"to":null},
        "delay_seconds":0,"delivery_mode":"device","variants":null,"control_group":null,"conversion":null}"""

    /** Objects merge key by key; arrays and scalars replace, as the shared cases do. */
    fun merge(base: JsonElement, changes: JsonElement): JsonElement {
        if (!base.isJsonObject || !changes.isJsonObject) return changes.deepCopy()
        val merged = base.asJsonObject.deepCopy()
        for ((key, value) in changes.asJsonObject.entrySet()) {
            merged.add(key, merged.get(key)?.let { merge(it, value) } ?: value.deepCopy())
        }
        return merged
    }

    fun json(changes: String = "{}"): JsonObject =
        merge(JsonParser.parseString(BASE), JsonParser.parseString(changes)).asJsonObject

    fun campaign(changes: String = "{}"): InAppCampaign =
        InAppDefinition.readCampaign(json(changes)) ?: error("not a supported campaign: $changes")

    fun presentation(
        campaign: InAppCampaign,
        title: String? = if (campaign.template.isImageOnly) null else "Hello",
        message: String? = if (campaign.template.isImageOnly) null else "Welcome",
        buttons: List<String> = campaign.content.buttons.map { it.text },
        urls: List<String> = emptyList(),
        isTest: Boolean = false,
        storeUrl: String? = null,
    ) = InAppPresentation(
        campaign = campaign,
        rendered = InAppRendered(title, message, buttons, urls),
        isTest = isTest,
        storeUrl = storeUrl,
    )

    /** An image cache whose downloads answer from `files` at once. */
    fun images(folder: File, files: Map<String, ByteArray> = emptyMap()) =
        InAppImages(folder, loader = { url, done -> done(files[url]) }, decodes = { true })

    /** Preloads and waits until every picture of the campaign is on disk. */
    fun preload(images: InAppImages, campaign: JsonElement) {
        images.preload(listOf(campaign))
        repeat(100) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (images.ready(campaign)) return
            Thread.sleep(10)
        }
        error("images never became ready")
    }
}
