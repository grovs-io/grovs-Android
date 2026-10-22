package io.grovs.example.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class GrovsColors(
    val background: Color,
    val surface: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val border: Color,
    val divider: Color,
    val chipFill: Color,
    val accent: Color,
    val accentSoft: Color,
    val ok: Color,
    val okSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val error: Color,
    val errorSoft: Color,
    val neutralDot: Color,
)

val LightGrovsColors = GrovsColors(
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF8FAFC),
    text = Color(0xFF020617),
    textMuted = Color(0xFF64748B),
    textFaint = Color(0xFF94A3B8),
    border = Color(0xFFE2E8F0),
    divider = Color(0xFFEBEBEB),
    chipFill = Color(0xFFF1F5F9),
    accent = Color(0xFF2A86FF),
    accentSoft = Color(0x142A86FF),
    ok = Color(0xFF1BA34B),
    okSoft = Color(0xFFDDFBE7),
    warning = Color(0xFFD97706),
    warningSoft = Color(0xFFFEF3C7),
    error = Color(0xFFDC2626),
    errorSoft = Color(0xFFFBE8E9),
    neutralDot = Color(0xFFCBD5E1),
)

val DarkGrovsColors = GrovsColors(
    background = Color(0xFF0A0A0A),
    surface = Color(0xFF171717),
    text = Color(0xFFFAFAFA),
    textMuted = Color(0xFFA1A1A1),
    textFaint = Color(0xFF737373),
    border = Color(0x26FFFFFF),
    divider = Color(0x1AFFFFFF),
    chipFill = Color(0xFF262626),
    accent = Color(0xFF50A0FF),
    accentSoft = Color(0x1F50A0FF),
    ok = Color(0xFF34C46A),
    okSoft = Color(0xFF1C5C31),
    warning = Color(0xFFF59E0B),
    warningSoft = Color(0x33F59E0B),
    error = Color(0xFFF87171),
    errorSoft = Color(0x33F87171),
    neutralDot = Color(0xFF525252),
)

val LocalGrovsColors = staticCompositionLocalOf { LightGrovsColors }
