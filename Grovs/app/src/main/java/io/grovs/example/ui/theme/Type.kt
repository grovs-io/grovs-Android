package io.grovs.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.grovs.example.R

val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)

val GeistMono = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
)

object AppType {
    val title = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = (-0.2).sp)
    val heading = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    val body = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
    val bodyStrong = body.copy(fontWeight = FontWeight.Medium)
    val label = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 12.sp)
    val caption = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)
    val mono = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp)
    val monoSmall = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Medium, fontSize = 10.sp)
    val amount = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
}

val AppTypography = Typography(
    titleLarge = AppType.title,
    titleMedium = AppType.heading,
    bodyLarge = AppType.body.copy(fontSize = 16.sp),
    bodyMedium = AppType.body,
    bodySmall = AppType.caption,
    labelLarge = AppType.bodyStrong,
    labelMedium = AppType.label,
    labelSmall = AppType.label.copy(fontSize = 11.sp),
)
