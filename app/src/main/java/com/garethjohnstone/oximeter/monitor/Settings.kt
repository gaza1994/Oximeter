package com.garethjohnstone.oximeter.monitor

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Starting points for alarm limits by patient size. These are the values you
 * would typically see set on a ward monitor, not a recommendation - always set
 * them to match the monitor already in the room.
 */
enum class LimitProfile(
    val label: String,
    val note: String,
    val spo2Low: Int,
    val hrLow: Int,
    val hrHigh: Int
) {
    Adult("Adult", "Typical adult ward limits", 90, 50, 120),
    Child("Child", "Roughly 1 to 12 years", 92, 60, 140),
    Infant("Infant", "Under 1 year", 92, 90, 180),
    Custom("Custom", "Set by hand", 0, 0, 0);

    companion object {
        fun matching(spo2Low: Int, hrLow: Int, hrHigh: Int): LimitProfile =
            entries.firstOrNull {
                it != Custom && it.spo2Low == spo2Low && it.hrLow == hrLow && it.hrHigh == hrHigh
            } ?: Custom
    }
}

data class Prefs(
    val spo2Low: Int = 92,
    val heartRateLow: Int = 90,
    val heartRateHigh: Int = 180,
    val alarmHoldSec: Int = 6,
    val signalLostSec: Int = 10,

    val pulseToneEnabled: Boolean = true,
    val pulseToneVolume: Int = 50,
    val alarmVolume: Int = 90,

    val plethSampleRateHz: Int = 60,
    /** How much waveform to hold back before playing it out. */
    val smoothingMs: Int = 750,
    val rememberedAddress: String = "",

    val themeId: String = "clinical",
    val dimDisplay: Boolean = false,
    val keepScreenOn: Boolean = true
) {
    val profile: LimitProfile
        get() = LimitProfile.matching(spo2Low, heartRateLow, heartRateHigh)

    fun withProfile(p: LimitProfile) =
        if (p == LimitProfile.Custom) this
        else copy(spo2Low = p.spo2Low, heartRateLow = p.hrLow, heartRateHigh = p.hrHigh)

    fun toLimits() = AlarmLimits(
        spo2Low = spo2Low,
        heartRateLow = heartRateLow,
        heartRateHigh = heartRateHigh,
        holdMs = alarmHoldSec * 1000L,
        signalLostMs = signalLostSec * 1000L
    )
}

class SettingsStore(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("oximeter", Context.MODE_PRIVATE)

    private val _prefs = MutableStateFlow(load())
    val prefs: StateFlow<Prefs> = _prefs.asStateFlow()

    fun update(block: (Prefs) -> Prefs) {
        val next = block(_prefs.value)
        save(next)
        _prefs.value = next
    }

    fun resetToDefaults() {
        val d = Prefs()
        save(d)
        _prefs.value = d
    }

    private fun load(): Prefs {
        val d = Prefs()
        return Prefs(
            spo2Low = sp.getInt(K_SPO2_LOW, d.spo2Low),
            heartRateLow = sp.getInt(K_HR_LOW, d.heartRateLow),
            heartRateHigh = sp.getInt(K_HR_HIGH, d.heartRateHigh),
            alarmHoldSec = sp.getInt(K_HOLD, d.alarmHoldSec),
            signalLostSec = sp.getInt(K_SIGNAL_LOST, d.signalLostSec),
            pulseToneEnabled = sp.getBoolean(K_PULSE_ON, d.pulseToneEnabled),
            pulseToneVolume = sp.getInt(K_PULSE_VOL, d.pulseToneVolume),
            alarmVolume = sp.getInt(K_ALARM_VOL, d.alarmVolume),
            plethSampleRateHz = sp.getInt(K_SAMPLE_RATE, d.plethSampleRateHz),
            smoothingMs = sp.getInt(K_SMOOTHING, d.smoothingMs),
            rememberedAddress = sp.getString(K_ADDRESS, d.rememberedAddress) ?: d.rememberedAddress,
            themeId = sp.getString(K_THEME, d.themeId) ?: d.themeId,
            dimDisplay = sp.getBoolean(K_DIM, d.dimDisplay),
            keepScreenOn = sp.getBoolean(K_AWAKE, d.keepScreenOn)
        )
    }

    private fun save(p: Prefs) {
        sp.edit()
            .putInt(K_SPO2_LOW, p.spo2Low)
            .putInt(K_HR_LOW, p.heartRateLow)
            .putInt(K_HR_HIGH, p.heartRateHigh)
            .putInt(K_HOLD, p.alarmHoldSec)
            .putInt(K_SIGNAL_LOST, p.signalLostSec)
            .putBoolean(K_PULSE_ON, p.pulseToneEnabled)
            .putInt(K_PULSE_VOL, p.pulseToneVolume)
            .putInt(K_ALARM_VOL, p.alarmVolume)
            .putInt(K_SAMPLE_RATE, p.plethSampleRateHz)
            .putInt(K_SMOOTHING, p.smoothingMs)
            .putString(K_ADDRESS, p.rememberedAddress)
            .putString(K_THEME, p.themeId)
            .putBoolean(K_DIM, p.dimDisplay)
            .putBoolean(K_AWAKE, p.keepScreenOn)
            .apply()
    }

    private companion object {
        const val K_SPO2_LOW = "spo2_low"
        const val K_HR_LOW = "hr_low"
        const val K_HR_HIGH = "hr_high"
        const val K_HOLD = "alarm_hold_sec"
        const val K_SIGNAL_LOST = "signal_lost_sec"
        const val K_PULSE_ON = "pulse_tone_on"
        const val K_PULSE_VOL = "pulse_volume"
        const val K_ALARM_VOL = "alarm_volume"
        const val K_SAMPLE_RATE = "pleth_sample_rate"
        const val K_SMOOTHING = "smoothing_ms"
        const val K_ADDRESS = "device_address"
        const val K_THEME = "theme_id"
        const val K_DIM = "dim_display"
        const val K_AWAKE = "keep_screen_on"
    }
}
