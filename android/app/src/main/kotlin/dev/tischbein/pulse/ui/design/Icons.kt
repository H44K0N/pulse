package dev.tischbein.pulse.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.tischbein.pulse.ui.theme.Pulse

/*
 * The handful of line icons the app needs, drawn on a 24-unit grid. Drawing them avoids the
 * material-icons dependency (several MB) for eight glyphs, and keeps one consistent stroke.
 */

enum class PulseIcon { TRAIN, PROGRESS, SETTINGS, HEART, BATTERY, SIGNAL, PAUSE, PLAY }

@Composable
fun Icon(
    icon: PulseIcon,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val stroke = Stroke(width = 1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        when (icon) {
            PulseIcon.TRAIN -> {
                // Stopwatch.
                drawCircle(tint, 8.5f * u, p(12f, 13.5f), style = stroke)
                drawLine(tint, p(12f, 13.5f), p(12f, 9f), stroke.width, StrokeCap.Round)
                drawLine(tint, p(9.5f, 2.5f), p(14.5f, 2.5f), stroke.width, StrokeCap.Round)
                drawLine(tint, p(12f, 2.5f), p(12f, 5f), stroke.width, StrokeCap.Round)
            }
            PulseIcon.PROGRESS -> {
                // Rising line over a baseline.
                val path = Path().apply {
                    moveTo(3f * u, 17f * u)
                    lineTo(9f * u, 11f * u)
                    lineTo(13f * u, 14f * u)
                    lineTo(21f * u, 6f * u)
                }
                drawPath(path, tint, style = stroke)
                drawLine(tint, p(3f, 21f), p(21f, 21f), stroke.width, StrokeCap.Round)
                drawLine(tint, p(16f, 6f), p(21f, 6f), stroke.width, StrokeCap.Round)
                drawLine(tint, p(21f, 6f), p(21f, 11f), stroke.width, StrokeCap.Round)
            }
            PulseIcon.SETTINGS -> {
                // Three sliders.
                listOf(5f to 8f, 12f to 16f, 19f to 10f).forEach { (y, knob) ->
                    drawLine(tint, p(3f, y), p(21f, y), stroke.width, StrokeCap.Round)
                    drawCircle(Pulse.bg, 2.6f * u, p(knob, y))
                    drawCircle(tint, 2.6f * u, p(knob, y), style = stroke)
                }
            }
            PulseIcon.HEART -> drawPath(heartPath(u), tint)
            PulseIcon.BATTERY -> {
                drawRoundRect(tint, p(2f, 7f), Size(18f * u, 10f * u), androidx.compose.ui.geometry.CornerRadius(2.5f * u), style = stroke)
                drawLine(tint, p(22.5f, 10.5f), p(22.5f, 13.5f), stroke.width, StrokeCap.Round)
                drawRoundRect(tint, p(4.5f, 9.5f), Size(13f * u, 5f * u), androidx.compose.ui.geometry.CornerRadius(1f * u))
            }
            PulseIcon.SIGNAL -> {
                // Electrode contact: a small ECG blip.
                val path = Path().apply {
                    moveTo(2f * u, 13f * u)
                    lineTo(7f * u, 13f * u)
                    lineTo(9.5f * u, 6f * u)
                    lineTo(13f * u, 19f * u)
                    lineTo(15.5f * u, 11f * u)
                    lineTo(17f * u, 13f * u)
                    lineTo(22f * u, 13f * u)
                }
                drawPath(path, tint, style = stroke)
            }
            PulseIcon.PAUSE -> {
                drawLine(tint, p(8.5f, 5f), p(8.5f, 19f), 3f * u, StrokeCap.Round)
                drawLine(tint, p(15.5f, 5f), p(15.5f, 19f), 3f * u, StrokeCap.Round)
            }
            PulseIcon.PLAY -> {
                val path = Path().apply {
                    moveTo(7f * u, 4.5f * u)
                    lineTo(19.5f * u, 12f * u)
                    lineTo(7f * u, 19.5f * u)
                    close()
                }
                drawPath(path, tint)
            }
        }
    }
}

private fun DrawScope.heartPath(u: Float) =
    Path().apply {
        moveTo(12f * u, 20.5f * u)
        cubicTo(4f * u, 15f * u, 2f * u, 11f * u, 2.5f * u, 8f * u)
        cubicTo(3f * u, 4.5f * u, 7.5f * u, 2.5f * u, 12f * u, 7f * u)
        cubicTo(16.5f * u, 2.5f * u, 21f * u, 4.5f * u, 21.5f * u, 8f * u)
        cubicTo(22f * u, 11f * u, 20f * u, 15f * u, 12f * u, 20.5f * u)
        close()
    }
