package dev.openstream.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/** The launcher icon's mark, drawn in the theme's colors: an open ring with a play mark inside. */
@Composable
fun Logo(size: Dp, modifier: Modifier = Modifier) {
    val ring = MaterialTheme.colorScheme.primary
    val mark = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.size(size)) {
        // The same numbers as res/drawable/ic_launcher_foreground.xml, moved onto a 48-unit square.
        val u = this.size.minDimension / 48f
        drawArc(
            ring, startAngle = -22f, sweepAngle = 314f, useCenter = false,
            topLeft = Offset(4 * u, 4 * u), size = Size(40 * u, 40 * u),
            style = Stroke(6 * u, cap = StrokeCap.Round),
        )
        val triangle = Path().apply {
            moveTo(19.5f * u, 15.5f * u)
            lineTo(19.5f * u, 32.5f * u)
            lineTo(34f * u, 24f * u)
            close()
        }
        drawPath(triangle, mark)
        drawPath(triangle, mark, style = Stroke(4.4f * u, join = StrokeJoin.Round))
    }
}
