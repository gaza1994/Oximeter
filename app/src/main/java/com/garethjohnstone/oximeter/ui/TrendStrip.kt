package com.garethjohnstone.oximeter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.garethjohnstone.oximeter.monitor.TrendPoint

/**
 * Ten minutes of saturation and pulse behind the live numbers. A single reading
 * cannot tell you whether a dip is a blip or the start of a pattern; this can.
 */
@Composable
private fun RowScope.Legend(text: String, colour: Color) {
    Text(text, color = colour, fontSize = 12.sp)
}

@Composable
fun TrendStrip(
    points: List<TrendPoint>,
    spo2Low: Int,
    minutes: Int,
    modifier: Modifier = Modifier
) {
    val spo2Colour = ink().Spo2
    val pulseColour = ink().Pulse
    val limitColour = ink().Alarm
    val gridColour = ink().Divider

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Legend("Last $minutes minutes", ink().Label)
            Spacer(Modifier.weight(1f))
            Legend("saturation", spo2Colour)
            Legend("  pulse", pulseColour)
        }

        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            if (points.size < 2) return@Canvas
            val w = size.width
            val h = size.height

            fun draw(values: List<Int?>, colour: Color, lo: Float, hi: Float) {
                val span = (hi - lo).coerceAtLeast(1f)
                var path: Path? = null
                values.forEachIndexed { i, v ->
                    val x = w * i / (values.size - 1).toFloat()
                    if (v == null) {
                        path?.let {
                            drawPath(it, colour, style = Stroke(2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                        path = null
                        return@forEachIndexed
                    }
                    val y = h * (1f - ((v - lo) / span)).coerceIn(0f, 1f)
                    if (path == null) path = Path().apply { moveTo(x, y) } else path!!.lineTo(x, y)
                }
                path?.let {
                    drawPath(it, colour, style = Stroke(2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }

            // Saturation on a fixed 80-100 scale: a floating scale would make a
            // one-percent wobble look like a crisis.
            val spo2Lo = 80f
            val spo2Hi = 100f
            val limitY = h * (1f - ((spo2Low - spo2Lo) / (spo2Hi - spo2Lo))).coerceIn(0f, 1f)
            drawLine(
                color = limitColour.copy(alpha = 0.45f),
                start = androidx.compose.ui.geometry.Offset(0f, limitY),
                end = androidx.compose.ui.geometry.Offset(w, limitY),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
            )
            drawLine(
                color = gridColour,
                start = androidx.compose.ui.geometry.Offset(0f, h),
                end = androidx.compose.ui.geometry.Offset(w, h),
                strokeWidth = 1f
            )

            draw(points.map { it.spo2 }, spo2Colour, spo2Lo, spo2Hi)

            val pulses = points.mapNotNull { it.pulse }
            if (pulses.isNotEmpty()) {
                val lo = (pulses.min() - 10).toFloat()
                val hi = (pulses.max() + 10).toFloat()
                draw(points.map { it.pulse }, pulseColour, lo, hi)
            }
        }
    }
}
