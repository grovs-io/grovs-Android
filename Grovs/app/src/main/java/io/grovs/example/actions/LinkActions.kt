package io.grovs.example.actions

import io.grovs.Grovs
import io.grovs.example.data.CallStyle
import io.grovs.example.data.LinkForm
import io.grovs.example.data.Redirect
import io.grovs.example.data.flatten
import io.grovs.example.data.toValueMap
import io.grovs.model.CustomLinkRedirect
import io.grovs.service.CustomRedirects
import io.grovs.service.TrackingParams
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object LinkActions {

    fun generate(form: LinkForm, style: CallStyle): TestAction {
        val title = form.title.ifBlank { null }
        val subtitle = form.subtitle.ifBlank { null }
        val image = form.imageUrl.ifBlank { null }
        val data = form.data.toValueMap()
        val tags = form.tags.ifEmpty { null }
        val redirects = if (form.redirectsOn) {
            CustomRedirects(ios = form.ios.toSdk(), android = form.android.toSdk(), desktop = form.desktop.toSdk())
        } else null
        val tracking = if (form.utmOn) {
            TrackingParams(
                utmCampaign = form.utmCampaign.ifBlank { null },
                utmSource = form.utmSource.ifBlank { null },
                utmMedium = form.utmMedium.ifBlank { null },
            )
        } else null
        val code = callCode(
            "generateLink",
            "title" to title,
            "subtitle" to subtitle,
            "imageURL" to image,
            "data" to data,
            "tags" to tags,
            "customRedirects" to if (form.redirectsOn) mapOf(
                "android" to form.android.describe(), "ios" to form.ios.describe(), "desktop" to form.desktop.describe(),
            ) else null,
            "showPreviewIos" to form.previewIos.toBoolean(),
            "showPreviewAndroid" to form.previewAndroid.toBoolean(),
            "copyToClipboardIos" to form.clipboardIos.toBoolean(),
            "copyToClipboardAndroid" to form.clipboardAndroid.toBoolean(),
            "tracking" to if (form.utmOn) mapOf(
                "utmSource" to form.utmSource, "utmMedium" to form.utmMedium, "utmCampaign" to form.utmCampaign,
            ) else null,
        )
        return TestAction(LogType.CREATE, "Generate link", code) {
            val link = when (style) {
                CallStyle.COROUTINE -> Grovs.generateLink(
                    title = title, subtitle = subtitle, imageURL = image, data = data, tags = tags,
                    customRedirects = redirects,
                    showPreviewIos = form.previewIos.toBoolean(), showPreviewAndroid = form.previewAndroid.toBoolean(),
                    copyToClipboardIos = form.clipboardIos.toBoolean(), copyToClipboardAndroid = form.clipboardAndroid.toBoolean(),
                    tracking = tracking,
                )
                CallStyle.CALLBACK -> suspendCancellableCoroutine { cont ->
                    Grovs.generateLink(
                        title = title, subtitle = subtitle, imageURL = image, data = data, tags = tags,
                        customRedirects = redirects,
                        showPreviewIos = form.previewIos.toBoolean(), showPreviewAndroid = form.previewAndroid.toBoolean(),
                        copyToClipboardIos = form.clipboardIos.toBoolean(), copyToClipboardAndroid = form.clipboardAndroid.toBoolean(),
                        tracking = tracking,
                        lifecycleOwner = null,
                        listener = { link, error ->
                            if (link != null) cont.resume(link)
                            else cont.resumeWithException(error ?: IllegalStateException("No link and no error"))
                        },
                    )
                }
            }
            ActionResult(LogState.OK, style.label, value = link, code = "$code\n\n-> \"$link\"")
        }
    }

    fun details(path: String, style: CallStyle): TestAction {
        val code = callCode("linkDetails", "path" to path)
        return TestAction(LogType.INSPECT, "Link details $path", code) {
            val details: Map<String, Any> = when (style) {
                CallStyle.COROUTINE -> Grovs.linkDetails(path)
                CallStyle.CALLBACK -> suspendCancellableCoroutine { cont ->
                    Grovs.linkDetails(path = path, lifecycleOwner = null, listener = { result, error ->
                        if (result != null) cont.resume(result)
                        else cont.resumeWithException(error ?: IllegalStateException("No details and no error"))
                    })
                }
            }
            ActionResult(LogState.OK, "${flatten(details).size} fields · ${style.label}", code = "$code\n\n-> ${render(details)}")
        }
    }

    private fun Redirect.toSdk(): CustomLinkRedirect? =
        url.takeIf { it.isNotBlank() }?.let { CustomLinkRedirect(link = it, openAppIfInstalled = openAppIfInstalled) }

    private fun Redirect.describe(): Any? = url.takeIf { it.isNotBlank() }?.let { mapOf("url" to it, "openAppIfInstalled" to openAppIfInstalled) }
}
