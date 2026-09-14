package com.garethjohnstone.oximeter.monitor

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.garethjohnstone.oximeter.audio.AlarmPriority
import com.garethjohnstone.oximeter.audio.ToneEngine
import com.garethjohnstone.oximeter.ble.FrameAssembler
import com.garethjohnstone.oximeter.ble.FrameParser
import com.garethjohnstone.oximeter.ble.DiscoveredDevice
import com.garethjohnstone.oximeter.ble.LinkState
import com.garethjohnstone.oximeter.ble.OximeterBle

private const val TAG = "Monitor"
private const val WAVE_POINTS = 360   // 6 seconds at 60 Hz, one sweep
private const val TREND_POINTS = 120  // 10 minutes at one point every 5s
private const val TREND_INTERVAL_MS = 5_000L
private const val PLAYBACK_TICK_MS = 33L   // ~30 redraws a second

data class MonitorUiState(
    val link: LinkState = LinkState.Idle,
    val spo2: Int? = null,
    val heartRate: Int? = null,
    val perfusionIndex: Float? = null,
    val deviceType: String? = null,
    val badChecksums: Int = 0,
    val barLevel: Int = 0,
    val signalQuality: SignalQuality = SignalQuality.Unknown,
    val wave: List<Int> = emptyList(),
    val waveCursor: Int = 0,
    val alarm: AlarmState = AlarmState(),
    val audioMuted: Boolean = false,
    val framesPerSecond: Float = 0f,
    val showSettings: Boolean = false,
    val trend: List<TrendPoint> = emptyList(),
    val alarmHistory: List<AlarmEvent> = emptyList(),
    val devices: List<DiscoveredDevice> = emptyList(),
    val note: String? = null
)

class MonitorViewModel(app: Application) : AndroidViewModel(app) {

    val settings = SettingsStore(app)
    val prefs: StateFlow<Prefs> get() = settings.prefs

    private val _ui = MutableStateFlow(MonitorUiState())
    val ui: StateFlow<MonitorUiState> = _ui.asStateFlow()

    private val tones = ToneEngine()
    private val beats = BeatDetector()
    private val signal = SignalQualityMeter()
    private val pacer = SamplePacer()
    private val alarms = AlarmEvaluator()
    private val ble = OximeterBle(app)
    private val history = AlarmHistory(app)

    // Ten minutes at one point every five seconds.
    private val trend = ArrayDeque<TrendPoint>(TREND_POINTS)
    private var lastTrendAt = 0L
    private val assembler = FrameAssembler(::onFrame)

    // Ring buffer, written in place so the trace sweeps rather than scrolls.
    private val wave = IntArray(WAVE_POINTS) { -1 }
    private var waveCursor = 0
    private var alarmJob: Job? = null
    private var lastAlarmKind = AlarmKind.None

    private var frameCount = 0
    private var frameWindowStart = System.currentTimeMillis()

    init {
        ble.onState = { s ->
            _ui.value = _ui.value.copy(link = s)
            if (s != LinkState.Connected) {
                assembler.reset()
                beats.reset()
                signal.reset()
                pacer.reset()
                wave.fill(-1)
                waveCursor = 0
            }
            refreshAlarm()
        }
        ble.onData = { assembler.feed(it) }
        ble.onDevices = { list -> _ui.value = _ui.value.copy(devices = list) }
        ble.onNote = { msg -> _ui.value = _ui.value.copy(note = msg) }
        tones.start()

        // Apply settings as they change.
        viewModelScope.launch {
            prefs.collect { p ->
                beats.sampleRateHz = p.plethSampleRateHz.toDouble()
                pacer.targetBuffer = (p.smoothingMs * p.plethSampleRateHz / 1000).coerceAtLeast(2)
                ble.preferredAddress = p.rememberedAddress.ifBlank { null }
                refreshAlarm()
            }
        }

        _ui.value = _ui.value.copy(alarmHistory = history.all())

        // Playback loop. Thirty updates a second, releasing samples at the rate
        // they were recorded at rather than the rate they arrive in.
        viewModelScope.launch {
            var last = System.currentTimeMillis()
            while (true) {
                delay(PLAYBACK_TICK_MS)
                val now = System.currentTimeMillis()
                val dt = (now - last).coerceAtMost(250L)
                last = now
                if (_ui.value.link != LinkState.Connected) continue

                var moved = false
                pacer.drain(dt) { v ->
                    wave[waveCursor] = v
                    waveCursor = (waveCursor + 1) % WAVE_POINTS
                    moved = true

                    if (beats.feedOne(v)) {
                        val p = prefs.value
                        if (p.pulseToneEnabled && !_ui.value.audioMuted &&
                            _ui.value.alarm.priority == AlarmPriority.None
                        ) {
                            tones.pulseBeep(_ui.value.spo2, p.pulseToneVolume / 100f)
                        }
                    }
                }
                if (moved) {
                    _ui.value = _ui.value.copy(wave = wave.toList(), waveCursor = waveCursor)
                }
            }
        }

        viewModelScope.launch { while (true) { delay(1_000); refreshAlarm(); sampleTrend() } }
    }

    fun start() = ble.start()

    fun stop() {
        ble.stop()
        alarmJob?.cancel()
        tones.flush()
    }

    private fun restartLink() { ble.stop(); ble.start() }

    fun pickDevice(address: String) {
        settings.update { it.copy(rememberedAddress = address) }
        _ui.value = _ui.value.copy(note = null)
        ble.connectToAddress(address)
    }

    fun forgetDevice() {
        settings.update { it.copy(rememberedAddress = "") }
        ble.preferredAddress = null
        restartLink()
    }

    fun rescan() {
        _ui.value = _ui.value.copy(note = null)
        ble.rescan()
    }

    fun openSettings() { _ui.value = _ui.value.copy(showSettings = true) }
    fun closeSettings() { _ui.value = _ui.value.copy(showSettings = false) }

    fun updatePrefs(block: (Prefs) -> Prefs) = settings.update(block)
    fun resetPrefs() = settings.resetToDefaults()

    /** Play the current alarm tone once, so limits can be set with the sound in mind. */
    fun previewAlarm(priority: AlarmPriority) {
        tones.alarmBurst(priority, prefs.value.alarmVolume / 100f)
    }

    fun previewPulse() {
        tones.pulseBeep(_ui.value.spo2 ?: 98, prefs.value.pulseToneVolume / 100f)
    }

    /** Plain on/off. Stays muted until it is switched back on. */
    fun toggleMute() {
        val muted = !_ui.value.audioMuted
        if (muted) tones.flush()
        _ui.value = _ui.value.copy(audioMuted = muted)
    }

    // --- data path ---------------------------------------------------------

    private fun onFrame(frame: ByteArray) {
        val live = FrameParser.parse(frame) ?: return
        countFrame()

        // Queue the waveform rather than drawing it here. Playing it out at a
        // steady rate is what keeps the sweep smooth and stops two beats inside
        // one frame collapsing into a single beep.
        pacer.offer(live.pleth)
        signal.feed(live.secondaryTrace, live.pleth)

        _ui.value = _ui.value.copy(
            spo2 = live.spo2,
            heartRate = live.heartRate,
            perfusionIndex = live.perfusionIndex,
            deviceType = FrameParser.deviceTypeName(live.deviceType),
            badChecksums = assembler.badChecksums,
            barLevel = signal.barLevel,
            signalQuality = signal.quality(),
        )
        refreshAlarm()
    }

    private fun countFrame() {
        frameCount++
        val now = System.currentTimeMillis()
        val elapsed = now - frameWindowStart
        if (elapsed >= 5_000) {
            _ui.value = _ui.value.copy(framesPerSecond = frameCount * 1000f / elapsed)
            frameCount = 0
            frameWindowStart = now
        }
    }

    private fun sampleTrend() {
        val now = System.currentTimeMillis()
        if (now - lastTrendAt < TREND_INTERVAL_MS) return
        lastTrendAt = now
        val s = _ui.value
        val connected = s.link == LinkState.Connected
        if (trend.size >= TREND_POINTS) trend.removeFirst()
        trend.addLast(
            TrendPoint(
                at = now,
                spo2 = if (connected) s.spo2 else null,
                pulse = if (connected) s.heartRate else null
            )
        )
        _ui.value = _ui.value.copy(trend = trend.toList())
    }

    fun clearHistory() {
        history.clear()
        _ui.value = _ui.value.copy(alarmHistory = history.all())

    }

    // --- alarms ------------------------------------------------------------

    private fun refreshAlarm() {
        val s = _ui.value
        val p = prefs.value
        val state = alarms.evaluate(
            limits = p.toLimits(),
            now = System.currentTimeMillis(),
            connected = s.link == LinkState.Connected,
            spo2 = s.spo2,
            heartRate = s.heartRate
        )
        if (state != s.alarm) _ui.value = _ui.value.copy(alarm = state)

        history.observe(s.spo2, s.heartRate)

        if (state.kind != lastAlarmKind) {
            // Latch what happened, so an alarm that came and went overnight is
            // still answerable in the morning.
            if (lastAlarmKind != AlarmKind.None) history.end()
            if (state.kind != AlarmKind.None) {
                history.start(state.kind, state.message, s.spo2, s.heartRate)
            }
            _ui.value = _ui.value.copy(alarmHistory = history.all())


            lastAlarmKind = state.kind
            alarmJob?.cancel()
            tones.flush()
            if (state.priority != AlarmPriority.None) {
                val period = if (state.priority == AlarmPriority.High) 2_500L else 6_000L
                alarmJob = viewModelScope.launch {
                    while (true) {
                        if (!_ui.value.audioMuted) {
                            tones.alarmBurst(state.priority, prefs.value.alarmVolume / 100f)
                        }
                        delay(period)
                    }
                }
            }
        }
    }

    override fun onCleared() {
        ble.stop()
        tones.stop()
        super.onCleared()
    }
}
