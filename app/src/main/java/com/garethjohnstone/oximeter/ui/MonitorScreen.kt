package com.garethjohnstone.oximeter.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.garethjohnstone.oximeter.audio.AlarmPriority
import com.garethjohnstone.oximeter.ble.LinkState
import com.garethjohnstone.oximeter.monitor.AlarmKind
import com.garethjohnstone.oximeter.monitor.MonitorUiState
import com.garethjohnstone.oximeter.monitor.SignalQuality

@Composable
fun MonitorScreen(
    state: MonitorUiState,
    status: SystemStatus,
    spo2Low: Int,
    onToggleMute: () -> Unit,
    onOpenSettings: () -> Unit,
    onGrantPermissions: () -> Unit
) {

    val alarming = state.alarm.priority != AlarmPriority.None
    val flash by rememberFlash(alarming && state.alarm.priority == AlarmPriority.High)

    // Large bright numerals sitting in one place all night will mark an OLED
    // panel. A slow drift of a few pixels over a couple of minutes is invisible
    // in use and enough to stop it.
    val drift = rememberInfiniteTransition(label = "burnIn")
    val driftX by drift.animateFloat(
        initialValue = -6f, targetValue = 6f,
        animationSpec = infiniteRepeatable(tween(97_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "driftX"
    )
    val driftY by drift.animateFloat(
        initialValue = 5f, targetValue = -5f,
        animationSpec = infiniteRepeatable(tween(131_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "driftY"
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(ink().Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .offset(x = driftX.dp, y = driftY.dp)
            .padding(horizontal = 20.dp)
    ) {
        StatusBar(state, onOpenSettings)

        if (state.link == LinkState.Connected) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The sensor's own pulse bar, mirrored.
                PulseBar(
                    level = state.barLevel,
                    quality = state.signalQuality,
                    modifier = Modifier.padding(end = 14.dp)
                )
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    PlethTrace(
                        samples = state.wave,
                        cursor = state.waveCursor,
                        colour = ink().Spo2
                    )
                }
            }
            if (state.signalQuality == SignalQuality.Poor ||
                state.signalQuality == SignalQuality.Fair
            ) {
                Text(
                    text = if (state.signalQuality == SignalQuality.Poor)
                        "Weak signal, the numbers may be unreliable"
                    else "Signal is marginal",
                    color = if (state.signalQuality == SignalQuality.Poor)
                        ink().Alarm else ink().Caution,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            Divider()
        } else {
            WaitingPanel(
                link = state.link,
                status = status,
                note = state.note,
                onGrantPermissions = onGrantPermissions,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            Divider()
        }

        Channel(
            label = "Oxygen saturation",
            value = state.spo2?.toString() ?: "--",
            unit = "%",
            colour = if (state.alarm.kind == AlarmKind.Spo2Low)
                ink().Alarm.copy(alpha = flash) else ink().Spo2,
            style = Numerals.Primary
        )

        Divider()

        Channel(
            label = "Pulse",
            value = state.heartRate?.toString() ?: "--",
            unit = "bpm",
            colour = if (state.alarm.kind == AlarmKind.HeartRateLow ||
                state.alarm.kind == AlarmKind.HeartRateHigh
            ) ink().Alarm.copy(alpha = flash) else ink().Pulse,
            style = Numerals.Secondary
        )

        Divider()

        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            SmallChannel(
                "Perfusion index",
                state.perfusionIndex?.let { String.format("%.2f", it) + "%" } ?: "-.--",
                ink().Respiration,
                Modifier.weight(1f)
            )
            SmallChannel(
                "Sensor",
                state.deviceType ?: "--",
                ink().Label,
                Modifier.weight(1f)
            )
        }

        if (state.trend.size > 2) {
            Divider()
            TrendStrip(
                points = state.trend,
                spo2Low = spo2Low,
                minutes = 10,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        Spacer(Modifier.weight(1f))

        AlarmBanner(state, onToggleMute)
    }
}

@Composable
private fun rememberFlash(active: Boolean) = if (!active) {
    androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
} else {
    val t = rememberInfiniteTransition(label = "alarm")
    t.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alarmAlpha"
    )
}

@Composable
private fun StatusBar(state: MonitorUiState, onOpenSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = linkText(state.link),
            color = if (state.link == LinkState.Connected) ink().Label else ink().Caution,
            fontSize = 15.sp
        )
        Spacer(Modifier.weight(1f))

        Text(
            text = "Settings",
            color = ink().Label,
            fontSize = 15.sp,
            modifier = Modifier
                .clickable { onOpenSettings() }
                .padding(start = 16.dp, top = 6.dp, bottom = 6.dp)
        )
    }
}

private fun linkText(s: LinkState) = when (s) {
    LinkState.Idle -> "Stopped"
    LinkState.Scanning -> "Looking for the sensor"
    LinkState.Connecting -> "Connecting"
    LinkState.Connected -> "Connected"
    LinkState.Reconnecting -> "Lost the sensor, retrying"
    LinkState.NoBluetooth -> "Turn Bluetooth on"
}


@Composable
private fun Channel(
    label: String,
    value: String,
    unit: String,
    colour: Color,
    style: TextStyle
) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)) {
        Text(label, color = ink().Label, fontSize = 16.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = colour, style = style)
            Spacer(Modifier.width(10.dp))
            Text(
                unit,
                color = colour.copy(alpha = 0.55f),
                fontSize = 22.sp,
                modifier = Modifier.padding(bottom = 18.dp)
            )
        }
    }
}

@Composable
private fun SmallChannel(label: String, value: String, colour: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = ink().Label, fontSize = 15.sp)
        Text(value, color = colour, style = Numerals.Tertiary)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(ink().Divider))
}

@Composable
private fun AlarmBanner(state: MonitorUiState, onToggleMute: () -> Unit) {
    val alarming = state.alarm.priority != AlarmPriority.None
    val tint = when (state.alarm.priority) {
        AlarmPriority.High -> ink().Alarm
        AlarmPriority.Medium -> ink().Caution
        AlarmPriority.None -> ink().Faint
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .background(if (alarming) tint.copy(alpha = 0.14f) else Color.Transparent)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (alarming) state.alarm.message else "No alarms",
            color = tint,
            fontSize = 19.sp,
            fontWeight = if (alarming) FontWeight.SemiBold else FontWeight.Normal
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (state.audioMuted) "Sound off" else "Sound on",
            color = if (state.audioMuted) ink().Caution else ink().Label,
            fontSize = 17.sp,
            modifier = Modifier
                .clickable { onToggleMute() }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}
