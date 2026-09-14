package com.garethjohnstone.oximeter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.garethjohnstone.oximeter.audio.AlarmPriority
import androidx.compose.runtime.remember
import com.garethjohnstone.oximeter.monitor.AlarmEvent
import com.garethjohnstone.oximeter.monitor.AlarmKind
import com.garethjohnstone.oximeter.monitor.LimitProfile
import com.garethjohnstone.oximeter.monitor.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Diagnostics(
    val framesPerSecond: Float,
    val badChecksums: Int,
    val deviceType: String?,
    val connected: Boolean
)

/**
 * Steppers rather than sliders or text fields: this gets used in a dark room,
 * half asleep, and a tap target you cannot overshoot beats precision.
 */
@Composable
fun SettingsScreen(
    prefs: Prefs,
    diagnostics: Diagnostics,
    devices: List<com.garethjohnstone.oximeter.ble.DiscoveredDevice>,
    onPickDevice: (String) -> Unit,
    onRescan: () -> Unit,
    alarmHistory: List<AlarmEvent>,
    onClearHistory: () -> Unit,
    onChange: ((Prefs) -> Prefs) -> Unit,
    onReset: () -> Unit,
    onForgetDevice: () -> Unit,
    onPreviewAlarm: (AlarmPriority) -> Unit,
    onPreviewPulse: () -> Unit,
    onClose: () -> Unit
) {
    val text = MaterialTheme.colorScheme.onBackground

    Column(
        Modifier
            .fillMaxSize()
            .background(ink().Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Settings", color = text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                "Done",
                color = ink().Spo2,
                fontSize = 18.sp,
                modifier = Modifier.clickable { onClose() }.padding(10.dp)
            )
        }

        // ---- who is wearing it -------------------------------------------

        SectionHeading("Patient", "Starting points only. Match the monitor in the room.")

        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            listOf(LimitProfile.Adult, LimitProfile.Child, LimitProfile.Infant).forEach { p ->
                Chip(
                    label = p.label,
                    selected = prefs.profile == p,
                    modifier = Modifier.weight(1f)
                ) { onChange { it.withProfile(p) } }
                Spacer(Modifier.width(8.dp))
            }
            Chip(
                label = "Custom",
                selected = prefs.profile == LimitProfile.Custom,
                modifier = Modifier.weight(1f)
            ) { }
        }
        Text(
            if (prefs.profile == LimitProfile.Custom) "Limits set by hand"
            else prefs.profile.note,
            color = ink().Label,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        // ---- limits -------------------------------------------------------

        SectionHeading("Alarm limits", null)

        Stepper(
            "Saturation alarms below", "${prefs.spo2Low}%", ink().Spo2,
            onDown = { onChange { it.copy(spo2Low = (it.spo2Low - 1).coerceAtLeast(80)) } },
            onUp = { onChange { it.copy(spo2Low = (it.spo2Low + 1).coerceAtMost(99)) } }
        )
        Stepper(
            "Pulse alarms below", "${prefs.heartRateLow} bpm", ink().Pulse,
            onDown = { onChange { it.copy(heartRateLow = (it.heartRateLow - 5).coerceAtLeast(30)) } },
            onUp = { onChange { it.copy(heartRateLow = (it.heartRateLow + 5).coerceAtMost(it.heartRateHigh - 5)) } }
        )
        Stepper(
            "Pulse alarms above", "${prefs.heartRateHigh} bpm", ink().Pulse,
            onDown = { onChange { it.copy(heartRateHigh = (it.heartRateHigh - 5).coerceAtLeast(it.heartRateLow + 5)) } },
            onUp = { onChange { it.copy(heartRateHigh = (it.heartRateHigh + 5).coerceAtMost(300)) } }
        )
        Stepper(
            "Wait before sounding", "${prefs.alarmHoldSec}s", ink().Caution,
            onDown = { onChange { it.copy(alarmHoldSec = (it.alarmHoldSec - 1).coerceAtLeast(0)) } },
            onUp = { onChange { it.copy(alarmHoldSec = (it.alarmHoldSec + 1).coerceAtMost(60)) } }
        )
        Stepper(
            "Sensor-off alarm after", "${prefs.signalLostSec}s", ink().Caution,
            onDown = { onChange { it.copy(signalLostSec = (it.signalLostSec - 5).coerceAtLeast(5)) } },
            onUp = { onChange { it.copy(signalLostSec = (it.signalLostSec + 5).coerceAtMost(120)) } }
        )

        // ---- sound --------------------------------------------------------

        SectionHeading("Sound", "Pulse tone pitch falls as saturation falls.")

        Stepper(
            "Smoothing delay", "${prefs.smoothingMs} ms", ink().Label,
            onDown = { onChange { it.copy(smoothingMs = (it.smoothingMs - 100).coerceAtLeast(100)) } },
            onUp = { onChange { it.copy(smoothingMs = (it.smoothingMs + 100).coerceAtMost(3000)) } }
        )
        Text(
            "Readings arrive in bursts twice a second. This much waveform is held " +
                "back so the trace and the beeps play out evenly. Lower is closer to " +
                "live but jerkier; higher is smoother but further behind.",
            color = ink().Label,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        Toggle("Beep on every beat", prefs.pulseToneEnabled) { on ->
            onChange { it.copy(pulseToneEnabled = on) }
        }
        Stepper(
            "Beep volume", "${prefs.pulseToneVolume}%", ink().Pulse,
            onDown = { onChange { it.copy(pulseToneVolume = (it.pulseToneVolume - 10).coerceAtLeast(0)) } },
            onUp = { onChange { it.copy(pulseToneVolume = (it.pulseToneVolume + 10).coerceAtMost(100)) } },
            trailing = { Play(onPreviewPulse) }
        )
        Stepper(
            "Alarm volume", "${prefs.alarmVolume}%", ink().Alarm,
            onDown = { onChange { it.copy(alarmVolume = (it.alarmVolume - 10).coerceAtLeast(10)) } },
            onUp = { onChange { it.copy(alarmVolume = (it.alarmVolume + 10).coerceAtMost(100)) } },
            trailing = { Play { onPreviewAlarm(AlarmPriority.High) } }
        )

        // ---- appearance ---------------------------------------------------

        SectionHeading("Appearance", null)

        Themes.all.forEach { theme ->
            ThemeRow(theme, prefs.themeId == theme.id) {
                onChange { it.copy(themeId = theme.id) }
            }
        }

        Spacer(Modifier.height(10.dp))
        Toggle("Keep the screen awake", prefs.keepScreenOn) { on ->
            onChange { it.copy(keepScreenOn = on) }
        }
        Toggle("Dim for night", prefs.dimDisplay) { on ->
            onChange { it.copy(dimDisplay = on) }
        }

        // ---- history -------------------------------------------------------

        SectionHeading(
            "Alarm history",
            if (alarmHistory.isEmpty()) "Nothing has alarmed yet."
            else "Most recent first. Kept when the app closes."
        )

        alarmHistory.take(12).forEach { event -> HistoryRow(event) }

        if (alarmHistory.isNotEmpty()) {
            Text(
                "Clear history",
                color = ink().Label,
                fontSize = 15.sp,
                modifier = Modifier.clickable { onClearHistory() }.padding(vertical = 10.dp)
            )
        }

        // ---- sensor --------------------------------------------------------

        SectionHeading(
            "Sensor",
            "Found automatically. Only pick one by hand if it does not connect."
        )

        DeviceList(
            devices = devices,
            rememberedAddress = prefs.rememberedAddress,
            onPick = onPickDevice,
            onRescan = onRescan
        )

        if (prefs.rememberedAddress.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Remembered sensor", color = text, fontSize = 17.sp)
                    Text(prefs.rememberedAddress, color = ink().Label, fontSize = 14.sp)
                }
                Text(
                    "Forget",
                    color = ink().Caution,
                    fontSize = 16.sp,
                    modifier = Modifier.clickable { onForgetDevice() }.padding(10.dp)
                )
            }
        }

        // ---- diagnostics ---------------------------------------------------

        SectionHeading("Diagnostics", "Only useful if something looks wrong.")

        Info("Link", if (diagnostics.connected) "connected" else "not connected")
        Info("Sensor type", diagnostics.deviceType ?: "unknown")
        Info(
            "Frame rate",
            if (diagnostics.framesPerSecond > 0)
                String.format("%.1f/s, %.0f Hz trace", diagnostics.framesPerSecond, diagnostics.framesPerSecond * 30)
            else "--"
        )
        Info("Bad frames", diagnostics.badChecksums.toString())
        Stepper(
            "Trace sample rate", "${prefs.plethSampleRateHz} Hz", ink().Label,
            onDown = { onChange { it.copy(plethSampleRateHz = (it.plethSampleRateHz - 5).coerceAtLeast(10)) } },
            onUp = { onChange { it.copy(plethSampleRateHz = (it.plethSampleRateHz + 5).coerceAtMost(250)) } }
        )

        Text(
            "Reset everything to defaults",
            color = ink().Label,
            fontSize = 16.sp,
            modifier = Modifier
                .padding(top = 20.dp, bottom = 40.dp)
                .clickable { onReset() }
                .padding(vertical = 10.dp)
        )
    }
}

@Composable
private fun HistoryRow(event: AlarmEvent) {
    val started = remember(event.startedAt) {
        SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(event.startedAt))
    }
    val lasted = event.endedAt?.let { end ->
        val secs = ((end - event.startedAt) / 1000).coerceAtLeast(1)
        if (secs < 60) "${secs}s" else "${secs / 60}m ${secs % 60}s"
    }
    val tint = when (event.kind) {
        AlarmKind.Spo2Low, AlarmKind.HeartRateLow, AlarmKind.HeartRateHigh -> ink().Alarm
        else -> ink().Caution
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(ink().Panel)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(event.message, color = tint, fontSize = 16.sp)
            Text(
                buildString {
                    append(started)
                    if (lasted != null) append("  for $lasted") else append("  ongoing")
                    event.worstSpo2?.let { append("  low ${'$'}it%") }
                    event.worstPulse?.let { append("  pulse ${'$'}it") }
                },
                color = ink().Label,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .background(if (selected) ink().Spo2.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, if (selected) ink().Spo2 else ink().Divider)
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) ink().Spo2 else ink().Label,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun ThemeRow(theme: Palette, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(if (selected) ink().Panel else Color.Transparent)
            .border(1.dp, if (selected) ink().Spo2 else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // A live swatch of the palette, so the choice is visible before picking.
        Row(Modifier.padding(end = 14.dp)) {
            listOf(theme.Background, theme.Spo2, theme.Pulse, theme.Alarm).forEach { c ->
                Box(
                    Modifier
                        .size(width = 14.dp, height = 28.dp)
                        .background(c)
                        .border(1.dp, theme.Divider)
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                theme.label,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 17.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(theme.note, color = ink().Label, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, color = ink().Label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp)
    }
}

@Composable
private fun SectionHeading(title: String, note: String?) {
    Column(Modifier.padding(top = 26.dp, bottom = 4.dp)) {
        Text(
            title,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (note != null) Text(note, color = ink().Label, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(ink().Divider))
    }
}

@Composable
private fun Stepper(
    label: String,
    value: String,
    accent: Color,
    onDown: () -> Unit,
    onUp: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = MaterialTheme.colorScheme.onBackground, fontSize = 17.sp)
            Text(value, color = accent, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        }
        trailing?.invoke()
        StepButton("\u2212", onDown)
        Spacer(Modifier.width(8.dp))
        StepButton("+", onUp)
    }
}

@Composable
private fun StepButton(glyph: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .background(ink().Panel)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(glyph, color = MaterialTheme.colorScheme.onBackground, fontSize = 24.sp)
    }
}

@Composable
private fun Play(onClick: () -> Unit) {
    Text(
        "Play",
        color = ink().Label,
        fontSize = 15.sp,
        modifier = Modifier.clickable { onClick() }.padding(horizontal = 12.dp, vertical = 10.dp)
    )
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 17.sp,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ink().Background,
                checkedTrackColor = ink().Spo2,
                uncheckedTrackColor = ink().Panel
            )
        )
    }
}
