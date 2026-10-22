package io.grovs.example.ui.controls

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val KEYBOARD_SETTLE_MS = 60L

/** Where the text fields on screen are, so a tap elsewhere can close the keyboard. */
class FieldAreas {
    private val areas = mutableMapOf<Any, Rect>()

    fun put(key: Any, area: Rect) {
        areas[key] = area
    }

    fun remove(key: Any) {
        areas.remove(key)
    }

    fun contain(point: Offset) = areas.values.any { it.contains(point) }
}

val LocalFieldAreas = staticCompositionLocalOf<FieldAreas?> { null }

/**
 * Clears focus, and so closes the keyboard, on a tap outside every text field. Watches taps
 * without consuming them, so the button or row that was tapped still reacts. Put it on the
 * full-screen root that provides [LocalFieldAreas].
 */
fun Modifier.clearFocusOnTapOutside(fields: FieldAreas): Modifier = composed {
    val focusManager = LocalFocusManager.current
    pointerInput(fields) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
            val isTap = (up.position - down.position).getDistance() < viewConfiguration.touchSlop
            if (isTap && !fields.contain(down.position)) focusManager.clearFocus()
        }
    }
}

/**
 * Marks a text field. Registers its area for [clearFocusOnTapOutside], and once the keyboard has
 * finished opening, scrolls the whole field into view with room for its label above and some
 * space below. Text fields on their own only reveal the cursor line.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.revealWhenFocused(): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    val fields = LocalFieldAreas.current
    val key = remember { Any() }
    DisposableEffect(fields) { onDispose { fields?.remove(key) } }
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
        .onGloballyPositioned { fields?.put(key, it.boundsInRoot()) }
        .onFocusEvent { focused = it.hasFocus }
}
