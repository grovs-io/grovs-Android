package io.grovs.example.ui.links

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.grovs.example.data.LinkForm
import io.grovs.example.data.Redirect
import io.grovs.example.data.SAMPLE_IMAGE_1
import io.grovs.example.data.SAMPLE_IMAGE_2
import io.grovs.example.data.SAMPLE_REDIRECTS
import io.grovs.example.ui.Help
import io.grovs.example.ui.controls.AppTextField
import io.grovs.example.ui.controls.ChipInput
import io.grovs.example.ui.controls.ChipPicker
import io.grovs.example.ui.controls.ChipRow
import io.grovs.example.ui.controls.FoldRow
import io.grovs.example.ui.controls.KeyValueRows
import io.grovs.example.ui.controls.LinkField
import io.grovs.example.ui.controls.SuggestField
import io.grovs.example.ui.controls.SwitchRow
import io.grovs.example.ui.controls.TriStateRow

@Composable
fun LinkFormFields(form: LinkForm, update: ((LinkForm) -> LinkForm) -> Unit) {
    SuggestField(form.title, { v -> update { it.copy(title = v) } }, emptyList(), label = "Title", imeAction = ImeAction.Next)
    Spacer(Modifier.height(10.dp))
    SuggestField(form.subtitle, { v -> update { it.copy(subtitle = v) } }, emptyList(), label = "Subtitle", imeAction = ImeAction.Next)
    Spacer(Modifier.height(10.dp))
    KeyValueRows(form.data, { v -> update { it.copy(data = v) } }, label = "Data", info = Help.linkData, suggestedKeys = listOf("promo", "user_id", "screen"))
    Spacer(Modifier.height(6.dp))
    FoldRow("More options", form.moreSummary()) {
        ChipPicker(
            options = listOf("", SAMPLE_IMAGE_1, SAMPLE_IMAGE_2),
            selected = form.imageUrl,
            onSelect = { v -> update { it.copy(imageUrl = v) } },
            label = "Image",
            otherLabel = "URL…",
            otherPlaceholder = "https://",
            display = { when (it) { "" -> "None"; SAMPLE_IMAGE_1 -> "Sample 1"; SAMPLE_IMAGE_2 -> "Sample 2"; else -> it } },
        )
        Spacer(Modifier.height(10.dp))
        ChipInput(form.tags, { v -> update { it.copy(tags = v) } }, label = "Tags", info = Help.linkTags, placeholder = "Add tag")
        SwitchRow("UTM tracking", form.utmOn, { v -> update { it.copy(utmOn = v) } }, info = Help.utm)
        if (form.utmOn) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                AppTextField(form.utmSource, { v -> update { it.copy(utmSource = v) } }, Modifier.weight(1f), placeholder = "source", imeAction = ImeAction.Next)
                AppTextField(form.utmMedium, { v -> update { it.copy(utmMedium = v) } }, Modifier.weight(1f), placeholder = "medium", imeAction = ImeAction.Next)
                AppTextField(form.utmCampaign, { v -> update { it.copy(utmCampaign = v) } }, Modifier.weight(1f), placeholder = "campaign")
            }
        }
        SwitchRow(
            "Custom redirects", form.redirectsOn, { v -> update { it.copy(redirectsOn = v) } },
            supporting = if (form.redirectsOn) null else "uses project", info = Help.customRedirects,
        )
        if (form.redirectsOn) {
            RedirectEditor("Android", form.android) { r -> update { it.copy(android = r) } }
            RedirectEditor("iOS", form.ios) { r -> update { it.copy(ios = r) } }
            RedirectEditor("Desktop", form.desktop) { r -> update { it.copy(desktop = r) } }
        }
        FoldRow("Platform overrides", form.overridesSummary(), info = Help.platformOverrides) {
            TriStateRow("Preview on Android", form.previewAndroid) { v -> update { it.copy(previewAndroid = v) } }
            TriStateRow("Preview on iOS", form.previewIos) { v -> update { it.copy(previewIos = v) } }
            TriStateRow("Clipboard on Android", form.clipboardAndroid) { v -> update { it.copy(clipboardAndroid = v) } }
            TriStateRow("Clipboard on iOS", form.clipboardIos) { v -> update { it.copy(clipboardIos = v) } }
        }
    }
}

@Composable
private fun RedirectEditor(platform: String, redirect: Redirect, onChange: (Redirect) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        LinkField(redirect.url, { onChange(redirect.copy(url = it)) }, label = platform)
        ChipRow(SAMPLE_REDIRECTS, redirect.url, { onChange(redirect.copy(url = it)) }, Modifier.padding(top = 6.dp))
        SwitchRow("Open app if installed", redirect.openAppIfInstalled, { onChange(redirect.copy(openAppIfInstalled = it)) }, showDivider = false, info = Help.openAppIfInstalled)
    }
}
