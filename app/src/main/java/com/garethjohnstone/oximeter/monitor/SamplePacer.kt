package com.garethjohnstone.oximeter.monitor

/**
 * Releases samples at a steady 60 Hz from data that arrives in 30-sample bursts
 * twice a second.
 *
 * Without this, everything downstream is quantised to frame arrival: the sweep
 * jumps 30 samples at a time, and the beat detector sees a whole half-second of
 * waveform in one call - so two beats inside one frame produce one beep.
 *
 * It holds a small buffer and gently varies the release rate to keep that buffer
 * near target, which absorbs Bluetooth jitter and any drift between the sensor's
 * real rate and a nominal 60 Hz. The cost is latency equal to the buffer depth,
 * which is well under a second and does not matter for watching a trend.
 */
class SamplePacer(
    private val nominalHz: Double = 60.0,
    /** Slack held in hand, in samples. Adjustable: see Prefs.smoothingMs. */
    var targetBuffer: Int = 45,
    private val maxBuffer: Int = 480
) {
    private val queue = ArrayDeque<Int>()
    private var credit = 0.0

    val depth: Int get() = queue.size

    fun offer(samples: IntArray) {
        for (v in samples) queue.addLast(v)
        // If we have fallen a long way behind - app resumed, link stalled - drop
        // the backlog rather than playing out stale waveform.
        while (queue.size > maxBuffer) queue.removeFirst()
    }

    /**
     * Release whatever is due after [dtMs] milliseconds, one sample at a time.
     */
    fun drain(dtMs: Long, onSample: (Int) -> Unit) {
        if (queue.isEmpty()) return

        // Nudge the rate up when the buffer is deep and down when it is shallow.
        val error = (queue.size - targetBuffer).toDouble()
        val rate = (nominalHz * (1.0 + error / 300.0))
            .coerceIn(nominalHz * 0.85, nominalHz * 1.4)

        credit += rate * dtMs / 1000.0
        while (credit >= 1.0 && queue.isNotEmpty()) {
            credit -= 1.0
            onSample(queue.removeFirst())
        }
        if (queue.isEmpty()) credit = 0.0
    }

    fun reset() {
        queue.clear()
        credit = 0.0
    }
}
