package com.garethjohnstone.oximeter.monitor

import com.garethjohnstone.oximeter.audio.AlarmPriority

/**
 * Alarm thresholds. Built from [Prefs] rather than hard-coded, so they can be
 * changed on the phone without a rebuild.
 */
data class AlarmLimits(
    val spo2Low: Int = 90,
    val heartRateLow: Int = 90,
    val heartRateHigh: Int = 180,
    /** How long a breach must persist before it sounds. Stops a wriggle firing it. */
    val holdMs: Long = 6_000,
    /** How long a dropout must persist before it counts as signal lost. */
    val signalLostMs: Long = 10_000
)

enum class AlarmKind { None, Spo2Low, HeartRateLow, HeartRateHigh, SignalLost, Disconnected }

data class AlarmState(
    val kind: AlarmKind = AlarmKind.None,
    val priority: AlarmPriority = AlarmPriority.None,
    val message: String = ""
)

/**
 * Decides what, if anything, should be sounding. Breaches have to persist for
 * [AlarmLimits.holdMs] before they count, which is what stops a finger twitch
 * setting the room off at 3am.
 */
class AlarmEvaluator {

    private var spo2BreachSince: Long? = null
    private var hrBreachSince: Long? = null
    private var lastGoodReadingAt: Long = 0L

    fun evaluate(
        limits: AlarmLimits,
        now: Long,
        connected: Boolean,
        spo2: Int?,
        heartRate: Int?
    ): AlarmState {

        if (!connected) {
            reset()
            return AlarmState(AlarmKind.Disconnected, AlarmPriority.Medium, "Not connected")
        }

        if (spo2 != null && heartRate != null) lastGoodReadingAt = now
        if (lastGoodReadingAt == 0L) lastGoodReadingAt = now

        // Technical alarm: probe off, or no usable trace.
        if (now - lastGoodReadingAt > limits.signalLostMs) {
            spo2BreachSince = null; hrBreachSince = null
            return AlarmState(AlarmKind.SignalLost, AlarmPriority.Medium, "Check the sensor")
        }

        spo2BreachSince = track(spo2BreachSince, now, spo2 != null && spo2 < limits.spo2Low)
        val hrBreached = heartRate != null &&
            (heartRate < limits.heartRateLow || heartRate > limits.heartRateHigh)
        hrBreachSince = track(hrBreachSince, now, hrBreached)

        spo2BreachSince?.let {
            if (now - it >= limits.holdMs) {
                return AlarmState(
                    AlarmKind.Spo2Low, AlarmPriority.High,
                    "SpO\u2082 below ${limits.spo2Low}"
                )
            }
        }

        hrBreachSince?.let {
            if (now - it >= limits.holdMs && heartRate != null) {
                return if (heartRate < limits.heartRateLow) {
                    AlarmState(AlarmKind.HeartRateLow, AlarmPriority.High,
                        "Pulse below ${limits.heartRateLow}")
                } else {
                    AlarmState(AlarmKind.HeartRateHigh, AlarmPriority.High,
                        "Pulse above ${limits.heartRateHigh}")
                }
            }
        }

        return AlarmState()
    }

    private fun track(since: Long?, now: Long, breached: Boolean): Long? =
        if (!breached) null else since ?: now

    fun reset() { spo2BreachSince = null; hrBreachSince = null; lastGoodReadingAt = 0L }
}
