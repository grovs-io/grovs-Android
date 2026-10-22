package io.grovs.example.ui.controls

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import io.grovs.example.ui.theme.AppTheme

/** Bottom sheet over the whole app. Without [heightFraction] it wraps its content and scrolls. */
@Composable
fun Sheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    heightFraction: Float? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = AppTheme.colors
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp
    BackHandler(enabled = visible, onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color(0x4D020617))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .then(if (heightFraction != null) Modifier.fillMaxHeight(heightFraction) else Modifier.heightIn(max = maxHeight))
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(c.background)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 14.dp)
                    .then(if (heightFraction == null) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            ) {
                Box(
                    Modifier.padding(top = 8.dp, bottom = 10.dp).size(36.dp, 4.dp)
                        .clip(RoundedCornerShape(2.dp)).background(c.neutralDot).align(Alignment.CenterHorizontally),
                )
                content()
            }
        }
    }
}

/** Lets any screen open a sheet that covers the whole app, tab bar included. */
class SheetHost {
    var content by mutableStateOf<(@Composable ColumnScope.() -> Unit)?>(null)
        private set

    fun show(content: @Composable ColumnScope.() -> Unit) {
        this.content = content
    }

    fun hide() {
        content = null
    }
}

val LocalSheetHost = staticCompositionLocalOf<SheetHost> { error("No SheetHost provided") }

@Composable
fun SheetHostView(host: SheetHost) {
    var last by remember { mutableStateOf(host.content) }
    host.content?.let { last = it }
    Sheet(visible = host.content != null, onDismiss = host::hide) {
        last?.invoke(this)
    }
}
