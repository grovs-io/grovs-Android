package io.grovs.example.ui.links

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.grovs.example.data.adbCommand
import io.grovs.example.data.qrMatrix
import io.grovs.example.ui.controls.AppButton
import io.grovs.example.ui.controls.ButtonStyle
import io.grovs.example.ui.controls.copyToClipboard
import io.grovs.example.ui.theme.AppTheme
import io.grovs.example.ui.theme.AppType

private fun qrBitmap(text: String, size: Int): ImageBitmap {
    val matrix = qrMatrix(text, size)
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) AndroidColor.BLACK else AndroidColor.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

@Composable
fun QrSheet(link: String) {
    val c = AppTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val bitmap = remember(link) { qrBitmap(link, 512) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Scan on another phone", style = AppType.heading, color = c.text)
        Text("Tests install and deferred deep links", style = AppType.caption, color = c.textMuted)
        Image(bitmap, contentDescription = "QR code for $link", modifier = Modifier.padding(vertical = 14.dp).size(220.dp))
        Text(link, style = AppType.mono, color = c.accent)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton("Copy link", { copyToClipboard(context, clipboard, link) }, Modifier.weight(1f), style = ButtonStyle.OUTLINE)
            AppButton("Copy adb", { copyToClipboard(context, clipboard, adbCommand(link, context.packageName)) }, Modifier.weight(1f), style = ButtonStyle.OUTLINE)
        }
    }
}
