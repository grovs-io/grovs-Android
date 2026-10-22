package io.grovs.example.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

object TabIcons {
    val Home = strokeIcon("Home", "M3 11 L12 3 L21 11 V20 a1 1 0 0 1 -1 1 H15 V15 H9 V21 H4 a1 1 0 0 1 -1 -1 Z")
    val Links = strokeIcon(
        "Links",
        "M10 14 a5 5 0 0 0 7 0 l3 -3 a5 5 0 0 0 -7 -7 l-1 1 M14 10 a5 5 0 0 0 -7 0 l-3 3 a5 5 0 0 0 7 7 l1 -1",
    )
    val Events = strokeIcon("Events", "M4 19 V9 M10 19 V5 M16 19 V12 M22 19 H2")
    val InApp = strokeIcon("InApp", "M4 5 H20 V16 H8 L4 20 Z")

    private fun strokeIcon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = PathParser().parsePathString(pathData).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()
}
