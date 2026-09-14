package com.garethjohnstone.oximeter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Plethysmograph drawn the way a bedside monitor draws one: a sweep.
 *
 * The trace writes left to right at a fixed position on screen, and a blank
 * erase gap runs just ahead of the write head wiping the previous sweep. The
 * waveform therefore sits still and gets overwritten in place, rather than
 * sliding across the screen. It is easier to read a shape that is not moving,
 * which is the whole reason monitors do it this way.
 *
 * [samples] is a ring buffer in index order, -1 for slots not yet written.
 * [cursor] is the next slot to be written, so the newest sample is at
 * cursor - 1 and the erase gap starts at cursor.
 */
@Composable
fun PlethTrace(
    samples: List<Int>,
    cursor: Int,
    colour: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val n = samples.size
        if (n < 4) return@Canvas

        val valid = samples.filter { it >= 0 }
        if (valid.size < 2) return@Canvas

        val h = size.height
        val pad = h * 0.08f
        val usable = h - pad * 2

        // Scale to what is actually on screen. The raw samples only occupy part
        // of the 0-127 range, so a fixed scale flattens the pulse.
        val lo = valid.min()
        val hi = valid.max()
        val span = (hi - lo).coerceAtLeast(8)

        val stepX = size.width / (n - 1).toFloat()
        fun xAt(i: Int) = i * stepX
        fun yAt(v: Int) = pad + usable * (1f - ((v - lo).toFloat() / span))

        // Blank space ahead of the write head, about a sixth of a second.
        val eraseGap = (n / 30).coerceAtLeast(4)

        fun erased(i: Int): Boolean {
            val ahead = (i - cursor + n) % n
            return ahead < eraseGap
        }

        // Walk the buffer in screen order, breaking the line wherever the erase
        // gap or an unwritten slot falls, so nothing joins across the wipe.
        var segment: MutableList<Int>? = null
        val segments = mutableListOf<List<Int>>()
        for (i in 0 until n) {
            val usable_i = samples[i] >= 0 && !erased(i)
            if (usable_i) {
                if (segment == null) segment = mutableListOf()
                segment.add(i)
            } else if (segment != null) {
                if (segment.size > 1) segments.add(segment)
                segment = null
            }
        }
        if (segment != null && segment.size > 1) segments.add(segment)

        for (seg in segments) {
            val line = Path().apply {
                moveTo(xAt(seg[0]), yAt(samples[seg[0]]))
                for (k in 1 until seg.size) {
                    val i0 = seg[k - 1]
                    val i1 = seg[k]
                    val x0 = xAt(i0)
                    val x1 = xAt(i1)
                    val mid = (x0 + x1) / 2f
                    cubicTo(mid, yAt(samples[i0]), mid, yAt(samples[i1]), x1, yAt(samples[i1]))
                }
            }
            drawUnder(line, seg, ::xAt, colour, pad, h)
            drawPath(
                path = line,
                color = colour,
                style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        // The write head, so it is obvious the trace is live and where it is.
        val newest = (cursor - 1 + n) % n
        if (samples[newest] >= 0) {
            drawCircle(
                color = colour,
                radius = 5f,
                center = Offset(xAt(newest), yAt(samples[newest]))
            )
        }
    }
}

private fun DrawScope.drawUnder(
    line: Path,
    seg: List<Int>,
    xAt: (Int) -> Float,
    colour: Color,
    top: Float,
    bottom: Float
) {
    val fill = Path().apply {
        addPath(line)
        lineTo(xAt(seg.last()), bottom)
        lineTo(xAt(seg.first()), bottom)
        close()
    }
    drawPath(
        path = fill,
        brush = Brush.verticalGradient(
            colors = listOf(colour.copy(alpha = 0.20f), colour.copy(alpha = 0f)),
            startY = top,
            endY = bottom
        )
    )
}
