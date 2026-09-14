package com.garethjohnstone.oximeter.monitor

import kotlin.math.sqrt

enum class SignalQuality { Unknown, Good, Fair, Poor }

/**
 * How trustworthy the current reading is, from the pulse-amplitude trace
 * (frame bytes 8-37) measured against the plethysmograph.
 *
 * Two independent things go wrong, and they look different in the data:
 *
 *  - poor coupling, the probe half on: the amplitude collapses (measured mean
 *    21 when seated properly, 10 when deliberately loose)
 *  - movement artefact: the amplitude holds up, because the sensor is still
 *    well seated, but the two traces stop agreeing (r 0.91 seated, 0.58 while
 *    the finger was being wiggled, 0.45 loose)
 *
 * So amplitude alone would miss movement and correlation alone would miss a
 * loose probe. Both are needed.
 */
class SignalQualityMeter(private val windowFrames: Int = 16) {   // 8 seconds

    /**
     * Damping. The underlying measure is noisy - perfusion genuinely wanders,
     * and a single awkward second should not relabel the reading. So a drop has
     * to hold for a few seconds before it is reported, recovery is quicker but
     * not instant, and the thresholds overlap so the state cannot oscillate on
     * a boundary.
     */
    private var reported = SignalQuality.Unknown
    private var candidate = SignalQuality.Unknown
    private var candidateSince = 0L

    private val amplitudes = ArrayDeque<Int>()
    private val barSamples = ArrayDeque<Int>()
    private val plethSamples = ArrayDeque<Int>()

    /** 0..31, follows the beat with a fast rise and a slower fall. */
    var barLevel: Int = 0
        private set

    fun feed(bar: IntArray, pleth: IntArray) {
        if (bar.isEmpty()) return

        val peak = bar.max()
        barLevel = maxOf(peak, (barLevel * 0.72f).toInt())

        if (amplitudes.size >= windowFrames) amplitudes.removeFirst()
        amplitudes.addLast(bar.max() - bar.min())

        val cap = windowFrames * bar.size
        for (v in bar) {
            if (barSamples.size >= cap) barSamples.removeFirst()
            barSamples.addLast(v)
        }
        for (v in pleth) {
            if (plethSamples.size >= cap) plethSamples.removeFirst()
            plethSamples.addLast(v)
        }
    }

    fun quality(now: Long = System.currentTimeMillis()): SignalQuality {
        if (amplitudes.size < windowFrames / 2) return SignalQuality.Unknown

        val instant = classify(medianAmplitude(), correlation())

        if (instant != candidate) {
            candidate = instant
            candidateSince = now
        }
        if (candidate != reported) {
            val dwell = if (rank(candidate) < rank(reported)) DOWNGRADE_MS else UPGRADE_MS
            if (now - candidateSince >= dwell) reported = candidate
        }
        return reported
    }

    /**
     * Thresholds are asymmetric: leaving a worse state needs a clearly better
     * measurement than entering it did, so a reading sitting on the line does
     * not flicker between two labels.
     */
    private fun classify(amplitude: Double, agreement: Double): SignalQuality {
        val worseThanGood = when (reported) {
            SignalQuality.Good -> amplitude < 13 || agreement < 0.70
            else -> amplitude < 17 || agreement < 0.80
        }
        if (!worseThanGood) return SignalQuality.Good

        val poor = when (reported) {
            SignalQuality.Poor -> amplitude < 10 || agreement < 0.58
            else -> amplitude < 7 || agreement < 0.48
        }
        return if (poor) SignalQuality.Poor else SignalQuality.Fair
    }

    /** Median rather than mean: one bad frame should not move it. */
    private fun medianAmplitude(): Double {
        val sorted = amplitudes.sorted()
        return sorted[sorted.size / 2].toDouble()
    }

    private fun rank(q: SignalQuality) = when (q) {
        SignalQuality.Good -> 2
        SignalQuality.Fair -> 1
        SignalQuality.Poor -> 0
        SignalQuality.Unknown -> 3
    }

    /** Pearson correlation between the two traces over the window. */
    private fun correlation(): Double {
        val n = minOf(barSamples.size, plethSamples.size)
        if (n < 30) return 1.0
        val a = barSamples.toList().takeLast(n)
        val b = plethSamples.toList().takeLast(n)
        val ma = a.average()
        val mb = b.average()
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in 0 until n) {
            val x = a[i] - ma
            val y = b[i] - mb
            num += x * y
            da += x * x
            db += y * y
        }
        val denom = sqrt(da) * sqrt(db)
        return if (denom < 1e-9) 1.0 else num / denom
    }

    fun reset() {
        reported = SignalQuality.Unknown
        candidate = SignalQuality.Unknown
        candidateSince = 0L
        amplitudes.clear()
        barSamples.clear()
        plethSamples.clear()
        barLevel = 0
    }

    private companion object {
        /** A drop has to persist this long before it is shown. */
        const val DOWNGRADE_MS = 5_000L
        /** Recovery is reported sooner, but still not on a single good frame. */
        const val UPGRADE_MS = 2_500L
    }
}
