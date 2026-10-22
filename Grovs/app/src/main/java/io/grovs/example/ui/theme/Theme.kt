package io.grovs.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

object AppTheme {
    val colors: GrovsColors
        @Composable get() = LocalGrovsColors.current
}

@Composable
fun GrovsTestAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkGrovsColors else LightGrovsColors
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent, onPrimary = Color.White,
            background = colors.background, onBackground = colors.text,
            surface = colors.background, onSurface = colors.text,
            surfaceVariant = colors.surface, outline = colors.border, error = colors.error,
        )
    } else {
        lightColorScheme(
            primary = colors.accent, onPrimary = Color.White,
            background = colors.background, onBackground = colors.text,
            surface = colors.background, onSurface = colors.text,
            surfaceVariant = colors.surface, outline = colors.border, error = colors.error,
        )
    }
    CompositionLocalProvider(LocalGrovsColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
