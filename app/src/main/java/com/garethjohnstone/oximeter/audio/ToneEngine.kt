package com.garethjohnstone.oximeter.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Small synth for the two sounds a pulse oximeter makes.
 *
 * Pulse tone: one short note per detected beat. Pitch falls as saturation falls,
 * which is the behaviour clinicians listen for rather than watch for. One
 * semitone per 2% here, so 100% sits at 880 Hz and 90% at about 659 Hz.
 *
 * Alarm tone: bursts modelled on IEC 60601-1-8. High priority is five pulses in
 * a 3+2 grouping, repeating every 2.5 s. Medium priority is three pulses,
 * repeating every 6 s, at a lower pitch.
 */
class ToneEngine {

    private val sampleRate = 44_100
    private val bufferFrames = 1_024
    private val queue = LinkedBlockingQueue<ShortArray>(32)
    private val silence = ShortArray(bufferFrames)

    @Volatile private var running = false
    private var track: AudioTrack? = null
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(bufferFrames * 4)

        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }

        worker = thread(name = "tone-engine", isDaemon = true) {
            while (running) {
                val buf = queue.poll() ?: silence
                track?.write(buf, 0, buf.size)
            }
        }
    }

    fun stop() {
        running = false
        worker?.join(250)
        worker = null
        runCatching { track?.stop(); track?.release() }
        track = null
        queue.clear()
    }

    /** Drop anything still queued, so a mute takes effect immediately. */
    fun flush() = queue.clear()

    // --- sounds ------------------------------------------------------------

    fun pulseBeep(spo2: Int?, volume: Float = 0.5f) {
        val sat = (spo2 ?: 100).coerceIn(70, 100)
        val freq = 880.0 * 2.0.pow((sat - 100) / 24.0)
        enqueue(tone(freq, 0.075, volume))
    }

    fun alarmBurst(priority: AlarmPriority, volume: Float = 0.9f) {
        when (priority) {
            AlarmPriority.High -> {
                val f = 960.0
                repeat(3) { enqueue(tone(f, 0.12, volume)); enqueue(gap(0.06)) }
                enqueue(gap(0.14))
                repeat(2) { enqueue(tone(f, 0.12, volume)); enqueue(gap(0.06)) }
            }
            AlarmPriority.Medium -> {
                val f = 620.0
                repeat(3) { enqueue(tone(f, 0.14, volume)); enqueue(gap(0.10)) }
            }
            AlarmPriority.None -> Unit
        }
    }

    // --- synthesis ---------------------------------------------------------

    private fun tone(freq: Double, seconds: Double, volume: Float): ShortArray {
        val n = (sampleRate * seconds).toInt()
        val ramp = (sampleRate * 0.006).toInt().coerceAtLeast(1)
        val out = ShortArray(n)
        for (i in 0 until n) {
            val env = when {
                i < ramp -> i.toDouble() / ramp
                i > n - ramp -> (n - i).toDouble() / ramp
                else -> 1.0
            }
            val s = sin(2.0 * PI * freq * i / sampleRate) * env * volume
            out[i] = (s * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun gap(seconds: Double) = ShortArray((sampleRate * seconds).toInt())

    private fun enqueue(samples: ShortArray) {
        var i = 0
        while (i < samples.size) {
            val end = minOf(i + bufferFrames, samples.size)
            val chunk = samples.copyOfRange(i, end)
            if (!queue.offer(chunk)) return        // queue full: drop rather than block
            i = end
        }
    }
}

enum class AlarmPriority { None, Medium, High }
