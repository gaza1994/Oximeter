package com.garethjohnstone.oximeter.monitor

import android.content.Context

/**
 * One alarm, from the moment it started sounding to the moment it cleared.
 * Held so that "did anything happen while I was downstairs" has an answer.
 */
data class AlarmEvent(
    val kind: AlarmKind,
    val message: String,
    val startedAt: Long,
    val endedAt: Long?,
    /** Worst value seen while it was active. */
    val worstSpo2: Int?,
    val worstPulse: Int?
) {
    fun encode(): String = listOf(
        kind.name, startedAt, endedAt ?: -1L,
        worstSpo2 ?: -1, worstPulse ?: -1,
        message.replace('|', '/')
    ).joinToString("|")

    companion object {
        fun decode(line: String): AlarmEvent? {
            val p = line.split("|")
            if (p.size < 6) return null
            return runCatching {
                AlarmEvent(
                    kind = AlarmKind.valueOf(p[0]),
                    startedAt = p[1].toLong(),
                    endedAt = p[2].toLong().takeIf { it >= 0 },
                    worstSpo2 = p[3].toInt().takeIf { it >= 0 },
                    worstPulse = p[4].toInt().takeIf { it >= 0 },
                    message = p.drop(5).joinToString("|")
                )
            }.getOrNull()
        }
    }
}

/** Survives a restart, so an overnight alarm is still there in the morning. */
class AlarmHistory(context: Context) {

    private val sp = context.getSharedPreferences("oximeter", Context.MODE_PRIVATE)
    private var events: MutableList<AlarmEvent> = load()

    fun all(): List<AlarmEvent> = events.toList()

    fun start(kind: AlarmKind, message: String, spo2: Int?, pulse: Int?) {
        events.add(0, AlarmEvent(kind, message, System.currentTimeMillis(), null, spo2, pulse))
        while (events.size > MAX) events.removeAt(events.size - 1)
        save()
    }

    /** Track the worst reading while an alarm is running. */
    fun observe(spo2: Int?, pulse: Int?) {
        val open = events.firstOrNull() ?: return
        if (open.endedAt != null) return
        val worseSpo2 = listOfNotNull(open.worstSpo2, spo2).minOrNull()
        val worsePulse = when {
            open.kind == AlarmKind.HeartRateHigh ->
                listOfNotNull(open.worstPulse, pulse).maxOrNull()
            else -> listOfNotNull(open.worstPulse, pulse).minOrNull()
        }
        if (worseSpo2 != open.worstSpo2 || worsePulse != open.worstPulse) {
            events[0] = open.copy(worstSpo2 = worseSpo2, worstPulse = worsePulse)
        }
    }

    fun end() {
        val open = events.firstOrNull() ?: return
        if (open.endedAt != null) return
        events[0] = open.copy(endedAt = System.currentTimeMillis())
        save()
    }

    fun clear() {
        events = mutableListOf()
        save()
    }

    private fun load(): MutableList<AlarmEvent> =
        (sp.getString(KEY, "") ?: "")
            .split("\n")
            .filter { it.isNotBlank() }
            .mapNotNull { AlarmEvent.decode(it) }
            .toMutableList()

    private fun save() {
        sp.edit().putString(KEY, events.joinToString("\n") { it.encode() }).apply()
    }

    private companion object {
        const val KEY = "alarm_history"
        const val MAX = 25
    }
}

/** One point on the trend strip. */
data class TrendPoint(val at: Long, val spo2: Int?, val pulse: Int?)
