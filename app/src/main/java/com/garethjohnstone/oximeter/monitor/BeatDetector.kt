package com.garethjohnstone.oximeter.monitor

/**
 * Finds beats in the plethysmograph stream so the pulse tone lands on the beat
 * rather than on a timer.
 *
 * Adaptive threshold: track the running min and max of the trace, fire on a
 * rising crossing of 60% of that range, then hold off for a refractory period.
 *
 * [sampleRateHz] is configurable because the real frame rate was not recoverable
 * from the vendor app - see PROTOCOL.md.
 */
class BeatDetector(
    var sampleRateHz: Double = 60.0,
    private val refractoryMs: Long = 280
) {
    private var min = 0.0
    private var max = 127.0
    private var last = 0.0
    private var armed = true
    private var lastBeatAt = 0L
    private var sampleClockMs = 0L

    /** Feed one frame's worth of samples. Returns true if any beat started. */
    fun feed(samples: IntArray): Boolean {
        var beat = false
        for (raw in samples) if (feedOne(raw)) beat = true
        return beat
    }

    /**
     * Feed a single sample. This is the one that matters: fed a whole frame at
     * once, two beats inside that frame collapse into a single result and one
     * of them is lost.
     */
    fun feedOne(raw: Int): Boolean {
        var beat = false
        val stepMs = 1000.0 / sampleRateHz.coerceAtLeast(1.0)

        run {
            val v = raw.toDouble()
            sampleClockMs += stepMs.toLong()

            // Decay the envelope so it tracks changing perfusion.
            min += (v - min) * if (v < min) 0.30 else 0.002
            max += (v - max) * if (v > max) 0.30 else 0.002

            val span = (max - min).coerceAtLeast(4.0)
            val threshold = min + span * 0.60

            if (!armed && v < min + span * 0.35) armed = true

            if (armed && last < threshold && v >= threshold &&
                sampleClockMs - lastBeatAt > refractoryMs
            ) {
                lastBeatAt = sampleClockMs
                armed = false
                beat = true
            }
            last = v
        }
        return beat
    }

    fun reset() {
        min = 0.0; max = 127.0; last = 0.0; armed = true
        lastBeatAt = 0L; sampleClockMs = 0L
    }
}
