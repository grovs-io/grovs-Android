package io.grovs.example.ui.controls

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val KEYBOARD_SETTLE_MS = 60L

/**
 * Scrolls the whole field into view, with room for its label above and some space below,
 * once the keyboard has finished opening. Text fields on their own only reveal the cursor line.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.revealWhenFocused(): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val keyboard = WindowInsets.ime.getBottom(density)
    val above = with(density) { 28.dp.toPx() }
    val below = with(density) { 16.dp.toPx() }
    LaunchedEffect(focused, keyboard) {
        if (!focused || keyboard == 0) return@LaunchedEffect
        // Restarts on every keyboard frame, so only the settled position scrolls.
        delay(KEYBOARD_SETTLE_MS)
        requester.bringIntoView(Rect(0f, -above, size.width.toFloat(), size.height + below))
    }
    this
        .bringIntoViewRequester(requester)
        .onSizeChanged { size = it }
        .onFocusEvent { focused = it.hasFocus }
}
