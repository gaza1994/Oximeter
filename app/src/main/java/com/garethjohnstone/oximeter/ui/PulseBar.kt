package com.garethjohnstone.oximeter.ui

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.garethjohnstone.oximeter.monitor.SignalQuality

/**
 * The jumping bar the sensor shows on its own screen, mirrored here. Driven by
 * frame bytes 8-37, which are a clipped, DC-stripped copy of the pulse.
 *
 * Segments rather than a smooth bar, because that is how the device draws it
 * and because discrete steps are easier to read out of the corner of an eye.
 */
@Composable
fun PulseBar(
    level: Int,
    quality: SignalQuality,
    modifier: Modifier = Modifier,
    segments: Int = 10
) {
    val lit by animateIntAsState(
        targetValue = ((level.coerceIn(0, 31) / 31f) * segments).toInt(),
        label = "barLevel"
    )

    val colour = when (quality) {
        SignalQuality.Poor -> ink().Alarm
        SignalQuality.Fair -> ink().Caution
        else -> ink().Pulse
    }

    Column(
        modifier.width(16.dp).fillMaxHeight(),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom
    ) {
        for (i in segments downTo 1) {
            Box(
                lit = i <= lit,
                colour = colour,
                modifier = Modifier.fillMaxWidth().height(8.dp).padding(vertical = 1.dp)
            )
        }
    }
}

@Composable
private fun Box(lit: Boolean, colour: Color, modifier: Modifier) {
    Spacer(
        modifier.background(if (lit) colour else colour.copy(alpha = 0.10f))
    )
}
