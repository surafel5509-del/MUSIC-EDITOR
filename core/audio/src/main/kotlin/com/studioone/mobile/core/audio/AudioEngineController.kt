package com.studioone.mobile.core.audio

import com.studioone.mobile.core.common.TimeMath
import com.studioone.mobile.core.model.AudioSettings
import com.studioone.mobile.core.model.AutomatableParameter
import com.studioone.mobile.core.model.EngineBufferSize
import com.studioone.mobile.core.model.FxPluginId
import com.studioone.mobile.core.model.LatencyReport
import com.studioone.mobile.core.model.LoudnessSnapshot
import com.studioone.mobile.core.model.MeterData
import com.studioone.mobile.core.model.TransportState
import com.studioone.mobile.core.model.BusId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The single Kotlin-facing audio engine API.
 *
 * Responsibilities:
 *  * Owns the native engine handle (create/start/stop/restart lifecycle).
 *  * Translates typed Kotlin calls into native commands.
 *  * Publishes transport state, meters, loudness, spectrum, and latency as
 *    Flows sampled at UI-friendly rates (meters ~30Hz, loudness ~10Hz).
 *
 * Thread-safety: all public methods are safe from any thread; the native
 * command queue serializes mutations onto the audio thread.
 */
@Singleton
class AudioEngineController @Inject constructor(
    private val deviceProfileProvider: DeviceProfileProvider,
) {
    private var handle: Long = 0L
    private val lock = Any()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _transportState = MutableStateFlow(TransportState.STOPPED)
    val transportState: StateFlow<TransportState> = _transportState.asStateFlow()

    private val _positionFrames = MutableStateFlow(0L)
    val positionFrames: StateFlow<Long> = _positionFrames.asStateFlow()

    private val _latency = MutableStateFlow<LatencyReport?>(null)
    val latency: StateFlow<LatencyReport?> = _latency.asStateFlow()

    private val _masterMeters = MutableStateFlow(MasterMeterFrame.EMPTY)
    val masterMeters: StateFlow<MasterMeterFrame> = _masterMeters.asStateFlow()

    private val _spectrum = MutableStateFlow<FloatArray?>(null)
    val spectrum: StateFlow<FloatArray?> = _spectrum.asStateFlow()

    private var telemetryJob: Job? = null
    private var settings: AudioSettings = AudioSettings()
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    val sampleRate: Int get() = settings.sampleRate.hz

    /**
     * Start the engine. Blocking (stream open) — call from a background
     * dispatcher. Returns false when no usable audio device exists.
     */
    fun start(settings: AudioSettings, inputEnabled: Boolean = true): Boolean = synchronized(lock) {
        if (handle != 0L) return true
        this.settings = settings
        val profile = deviceProfileProvider.stripBudget()
        val h = NativeAudioEngine.nativeCreate(
            sampleRate = settings.sampleRate.hz,
            framesPerBlock = settings.bufferSize.frames,
            maxStrips = profile.maxStrips,
            maxInstrumentStrips = profile.maxInstrumentStrips,
            lowLatency = settings.lowLatencyMode,
            exclusive = settings.exclusiveMode,
            inputEnabled = inputEnabled,
        )
        if (h == 0L) {
            Timber.e("native engine create failed")
            return false
        }
        handle = h
        _isRunning.value = true
        startTelemetry()
        Timber.i("audio engine started rate=${settings.sampleRate.hz} buf=${settings.bufferSize.frames}")
        true
    }

    fun stop() = synchronized(lock) {
        telemetryJob?.cancel()
        telemetryJob = null
        if (handle != 0L) {
            NativeAudioEngine.nativeDestroy(handle)
            handle = 0L
        }
        _isRunning.value = false
        _transportState.value = TransportState.STOPPED
    }

    /** Reopen streams after device change or audio settings change. */
    fun restart(settings: AudioSettings? = null): Boolean = synchronized(lock) {
        if (handle == 0L) return settings?.let { start(it) } ?: false
        settings?.let { this.settings = it }
        NativeAudioEngine.nativeRestart(handle)
    }

    // ── Transport ────────────────────────────────────────────────────────────

    fun play() = send(NativeAudioEngine.CommandType.PLAY, 0, 1, 0f)
    fun stopTransport() = send(NativeAudioEngine.CommandType.STOP, 0, 0, 0f)
    fun pause() = send(NativeAudioEngine.CommandType.PAUSE, 0, 0, 0f)
    fun startRecording() = send(NativeAudioEngine.CommandType.RECORD_START, 0, 1, 0f)

    fun seek(positionFrames: Long) =
        send(NativeAudioEngine.CommandType.SET_POSITION_FRAMES, 0, 0, 0f, lval = positionFrames)

    fun setLoop(enabled: Boolean, startFrame: Long, endFrame: Long) = send(
        NativeAudioEngine.CommandType.SET_LOOP_REGION, 0, if (enabled) 1 else 0, 0f,
        lval = startFrame, lval2 = endFrame,
    )

    fun setTempo(bpm: Double) =
        send(NativeAudioEngine.CommandType.SET_TEMPO, 0, 0, bpm.toFloat())

    fun setTimeSignature(numerator: Int, beatUnit: Int) =
        send(NativeAudioEngine.CommandType.SET_TIME_SIGNATURE, 0, numerator, 0f, arg2 = beatUnit)

    fun setMetronome(enabled: Boolean, volume: Float) =
        send(NativeAudioEngine.CommandType.SET_METRONOME, 0, if (enabled) 1 else 0, volume)

    fun setCountIn(bars: Int) =
        send(NativeAudioEngine.CommandType.SET_COUNT_IN, 0, bars, 0f)

    fun panic() = send(NativeAudioEngine.CommandType.PANIC, 0, 0, 0f)

    // ── Strips ───────────────────────────────────────────────────────────────

    fun addStrip(nativeHandle: Int) = send(NativeAudioEngine.CommandType.ADD_STRIP, nativeHandle, 0, 0f)
    fun removeStrip(nativeHandle: Int) = send(NativeAudioEngine.CommandType.REMOVE_STRIP, nativeHandle, 0, 0f)
    fun setStripGain(nativeHandle: Int, db: Float) =
        send(NativeAudioEngine.CommandType.SET_STRIP_GAIN_DB, nativeHandle, 0, db)
    fun setStripPan(nativeHandle: Int, pan: Float) =
        send(NativeAudioEngine.CommandType.SET_STRIP_PAN, nativeHandle, 0, pan)
    fun setStripWidth(nativeHandle: Int, width: Float) =
        send(NativeAudioEngine.CommandType.SET_STRIP_WIDTH, nativeHandle, 0, width)
    fun setStripMute(nativeHandle: Int, mute: Boolean) =
        send(NativeAudioEngine.CommandType.SET_STRIP_MUTE, nativeHandle, if (mute) 1 else 0, 0f)
    fun setStripSolo(nativeHandle: Int, solo: Boolean) =
        send(NativeAudioEngine.CommandType.SET_STRIP_SOLO, nativeHandle, if (solo) 1 else 0, 0f)
    fun setStripInputGain(nativeHandle: Int, db: Float) =
        send(NativeAudioEngine.CommandType.SET_STRIP_INPUT_GAIN, nativeHandle, 0, db)
    fun setStripArmed(nativeHandle: Int, armed: Boolean) =
        send(NativeAudioEngine.CommandType.ARM_STRIP, nativeHandle, if (armed) 1 else 0, 0f)
    fun setMonitorMode(nativeHandle: Int, mode: Int) =
        send(NativeAudioEngine.CommandType.SET_MONITOR_MODE, nativeHandle, mode, 0f)
    fun setSourceActive(nativeHandle: Int, active: Boolean) =
        send(NativeAudioEngine.CommandType.SET_SOURCE_ACTIVE, nativeHandle, if (active) 1 else 0, 0f)
    fun seekSource(nativeHandle: Int) =
        send(NativeAudioEngine.CommandType.SEEK_SOURCE, nativeHandle, 0, 0f)

    fun setSend(nativeHandle: Int, sendIndex: Int, levelDb: Float, pan: Float = 0f, preFader: Boolean = true) {
        send(NativeAudioEngine.CommandType.SET_SEND_LEVEL, nativeHandle, sendIndex, levelDb)
        send(NativeAudioEngine.CommandType.SET_SEND_PAN, nativeHandle, sendIndex, pan)
        send(NativeAudioEngine.CommandType.SET_SEND_ENABLED, nativeHandle, sendIndex, if (preFader) 1f else 1f)
    }

    // ── FX ───────────────────────────────────────────────────────────────────

    fun setFxSlot(nativeHandle: Int, slot: Int, plugin: FxPluginId?, params: Map<Int, Float> = emptyMap()) {
        val h = handle
        if (h == 0L) return
        val pairs = params.entries.flatMap { listOf(it.key.toFloat(), it.value) }.toFloatArray()
        NativeAudioEngine.nativeSetFxSlot(h, nativeHandle, slot, plugin?.nativeId ?: 0, pairs.ifEmpty { null })
    }

    fun setFxParam(nativeHandle: Int, slot: Int, paramIndex: Int, value: Float) = send(
        NativeAudioEngine.CommandType.SET_FX_PARAM, nativeHandle, slot, value, arg2 = paramIndex,
    )

    fun setFxBypass(nativeHandle: Int, slot: Int, bypassed: Boolean) =
        send(NativeAudioEngine.CommandType.SET_FX_BYPASS, nativeHandle, slot, if (bypassed) 1f else 0f)

    fun moveFxSlot(nativeHandle: Int, from: Int, to: Int) = send(
        NativeAudioEngine.CommandType.MOVE_FX_SLOT, nativeHandle, from, 0f, arg2 = to,
    )

    fun uploadAutomation(nativeHandle: Int, param: AutomatableParameter, pointsFramesAndValues: FloatArray) {
        val h = handle
        if (h == 0L || pointsFramesAndValues.isEmpty()) return
        NativeAudioEngine.nativeUploadAutomation(h, nativeHandle, param.paramId, pointsFramesAndValues)
    }

    fun setMasterLimiter(enabled: Boolean, ceilingDb: Float) = send(
        NativeAudioEngine.CommandType.SET_MASTER_LIMITER, 0, if (enabled) 1 else 0, ceilingDb, arg2 = if (enabled) 1 else 0,
    )

    // ── Instruments ──────────────────────────────────────────────────────────

    fun setInstrument(nativeHandle: Int, instrumentType: Int, presetBlob: FloatArray?) {
        val h = handle
        if (h == 0L) return
        NativeAudioEngine.nativeSetInstrument(h, nativeHandle, instrumentType, presetBlob)
    }

    fun setMacro(nativeHandle: Int, macroIndex: Int, value: Float) =
        send(NativeAudioEngine.CommandType.SET_INSTRUMENT_MACRO, nativeHandle, macroIndex, value)

    fun setArpeggiator(nativeHandle: Int, enabled: Boolean, mode: Int, divisionBeats: Float, gate: Float, octaves: Int) {
        send(
            NativeAudioEngine.CommandType.SET_ARPEGGIATOR, nativeHandle,
            if (enabled) 1 else 0, divisionBeats, arg2 = mode,
        )
        // gate/octaves ride on fval2/lval of the same command in the native parser.
        send(NativeAudioEngine.CommandType.SET_ARPEGGIATOR, nativeHandle, octaves, gate)
    }

    fun sendMidiNoteOn(nativeHandle: Int, key: Int, velocity: Int, channel: Int = 0, sampleOffset: Int = 0) = midi(
        nativeHandle, sampleOffset, type = 1, channel = channel, key = key, velocity = velocity,
    )

    fun sendMidiNoteOff(nativeHandle: Int, key: Int, channel: Int = 0, sampleOffset: Int = 0) = midi(
        nativeHandle, sampleOffset, type = 0, channel = channel, key = key, velocity = 0,
    )

    fun sendMidiCc(nativeHandle: Int, cc: Int, value01: Float, channel: Int = 0) = midi(
        nativeHandle, 0, type = 2, channel = channel, key = cc, velocity = 0, fval = value01,
    )

    fun sendMidiPitchBend(nativeHandle: Int, bend: Int, channel: Int = 0) = midi(
        nativeHandle, 0, type = 3, channel = channel, key = 0, velocity = 0, bend = bend,
        fval = bend / 8192f,
    )

    fun sendMidiPressure(nativeHandle: Int, value01: Float, channel: Int = 0) = midi(
        nativeHandle, 0, type = 4, channel = channel, key = 0, velocity = 0, fval = value01,
    )

    // ── Samples ──────────────────────────────────────────────────────────────

    fun loadSample(interleaved: FloatArray, channels: Int, sampleRate: Int): Int {
        val h = handle
        if (h == 0L) return -1
        val slot = NativeAudioEngine.nativeAllocSampleSlot(h)
        if (slot < 0) return -1
        if (!NativeAudioEngine.nativeLoadSample(h, slot, interleaved, channels, sampleRate)) return -1
        return slot
    }

    fun unloadSample(slot: Int) {
        val h = handle
        if (h != 0L) NativeAudioEngine.nativeUnloadSample(h, slot)
    }

    // ── Playback feed ────────────────────────────────────────────────────────

    fun feedTrack(nativeHandle: Int, left: FloatArray, right: FloatArray, frames: Int): Int {
        val h = handle
        if (h == 0L) return 0
        return NativeAudioEngine.nativeFeedTrack(h, nativeHandle, left, right, frames)
    }

    fun feedWritable(nativeHandle: Int): Int {
        val h = handle
        return if (h == 0L) 0 else NativeAudioEngine.nativeFeedWritable(h, nativeHandle)
    }

    // ── Recording takes ──────────────────────────────────────────────────────

    fun beginTake(nativeHandle: Int, wavPath: String, channels: Int, bitsPerSample: Int): Int {
        val h = handle
        return if (h == 0L) -1 else NativeAudioEngine.nativeBeginTake(h, nativeHandle, wavPath, channels, bitsPerSample)
    }

    /** Returns frames written, or null on I/O failure. */
    fun endTake(takeIndex: Int): Long? {
        val h = handle
        if (h == 0L) return null
        val result = NativeAudioEngine.nativeEndTake(h, takeIndex)
        return if (result >= 0) result else null
    }

    /** Pull one strip's meter snapshot (mixer UI, ~30Hz). */
    fun pullStripMeters(handle: Int, out: FloatArray): Boolean {
        val engineHandle = synchronized(lock) { this.handle }
        if (engineHandle == 0L) return false
        return NativeAudioEngine.nativePullStripMeters(engineHandle, handle, out)
    }

    fun takeStats(takeIndex: Int): Triple<Long, Long, Boolean> {
        val out = LongArray(3)
        val h = handle
        if (h != 0L) NativeAudioEngine.nativeTakeStats(h, takeIndex, out)
        return Triple(out[0], out[1], out[2] != 0L)
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun send(type: Int, target: Int, arg1: Int, fval: Float, arg2: Int = 0, fval2: Float = 0f, lval: Long = 0, lval2: Long = 0) {
        val h = handle
        if (h == 0L) return
        NativeAudioEngine.nativeSendCommand(h, type, target, arg1, arg2, fval, fval2, lval, lval2)
    }

    private fun midi(strip: Int, offset: Int, type: Int, channel: Int, key: Int, velocity: Int, bend: Int = 0, fval: Float = 0f) {
        val h = handle
        if (h == 0L) return
        NativeAudioEngine.nativeSendMidi(h, strip, offset, type, channel, key, velocity, bend, fval)
    }

    /**
     * Polling telemetry loop. Native meter state is lock-free to read, so we
     * sample on a fixed cadence instead of pushing from the audio thread
     * (which must never call JNI).
     */
    private fun startTelemetry() {
        telemetryJob?.cancel()
        telemetryJob = scope.launch {
            val masterBuf = FloatArray(12)
            val spectrumBuf = FloatArray(64)
            val latencyFloats = FloatArray(3)
            val latencyInts = IntArray(6)
            while (isActive) {
                val h = handle
                if (h == 0L) break
                NativeAudioEngine.nativePullMasterMeters(h, masterBuf)
                _masterMeters.value = MasterMeterFrame(
                    peakL = masterBuf[0], peakR = masterBuf[1],
                    rmsL = masterBuf[2], rmsR = masterBuf[3],
                    clipL = masterBuf[4] > 0.5f, clipR = masterBuf[5] > 0.5f,
                    loudness = LoudnessSnapshot(
                        momentaryLUFS = masterBuf[6], shortTermLUFS = masterBuf[7],
                        integratedLUFS = masterBuf[8], loudnessRangeLU = masterBuf[9],
                        truePeakDbtp = masterBuf[10],
                    ),
                    fxPoolPressure = masterBuf[11].toInt(),
                )
                if (NativeAudioEngine.nativePullSpectrum(h, spectrumBuf)) {
                    _spectrum.value = spectrumBuf.copyOf()
                }
                _positionFrames.value = NativeAudioEngine.nativeTransportPosition(h)
                _transportState.value = when (NativeAudioEngine.nativeTransportState(h)) {
                    1 -> TransportState.PLAYING
                    2 -> TransportState.RECORDING
                    3 -> TransportState.PAUSED
                    4 -> TransportState.WINDING // count-in
                    else -> TransportState.STOPPED
                }
                NativeAudioEngine.nativeLatencyReport(h, latencyFloats, latencyInts)
                _latency.value = LatencyReport(
                    inputLatencyMs = latencyFloats[0], outputLatencyMs = latencyFloats[1],
                    roundTripMs = latencyFloats[2], bufferFrames = latencyInts[0],
                    sampleRate = latencyInts[1], underrunCount = latencyInts[2].toLong(),
                    xrunCount = latencyInts[3].toLong(), usingMmap = latencyInts[4] == 1,
                    backendName = if (latencyInts[5] == 1) "AAudio" else "OpenSL ES",
                )
                NativeAudioEngine.nativeDrainReclaim(h)
                delay(33) // ~30Hz UI cadence
            }
        }
    }
}

/** Master bus meter frame published to the mixer UI. */
data class MasterMeterFrame(
    val peakL: Float, val peakR: Float,
    val rmsL: Float, val rmsR: Float,
    val clipL: Boolean, val clipR: Boolean,
    val loudness: LoudnessSnapshot,
    val fxPoolPressure: Int,
) {
    companion object {
        val EMPTY = MasterMeterFrame(0f, 0f, 0f, 0f, false, false,
            LoudnessSnapshot(-70f, -70f, -70f, 0f, -70f), 0)
    }
}

/** Strip-budget profile per device class (see core:common DeviceProfile). */
data class StripBudget(val maxStrips: Int, val maxInstrumentStrips: Int)

interface DeviceProfileProvider {
    fun stripBudget(): StripBudget
}

/** Native ids for FxPluginId — mirrors graph/FxChainRuntime.h::NativePluginId. */
val FxPluginId.nativeId: Int
    get() = when (this) {
        FxPluginId.COMPRESSOR -> 1
        FxPluginId.LIMITER -> 2
        FxPluginId.GATE -> 3
        FxPluginId.EXPANDER -> 4
        FxPluginId.DE_ESSER -> 5
        FxPluginId.PARAMETRIC_EQ -> 6
        FxPluginId.HIGH_PASS -> 7
        FxPluginId.LOW_PASS -> 8
        FxPluginId.AUTO_FILTER -> 9
        FxPluginId.REVERB -> 10
        FxPluginId.DELAY -> 11
        FxPluginId.PING_PONG_DELAY -> 12
        FxPluginId.CHORUS -> 13
        FxPluginId.FLANGER -> 14
        FxPluginId.PHASER -> 15
        FxPluginId.TREMOLO -> 16
        FxPluginId.AUTOPAN -> 17
        FxPluginId.DISTORTION -> 18
        FxPluginId.OVERDRIVE -> 19
        FxPluginId.BITCRUSHER -> 20
        FxPluginId.AMP_SIM -> 21
        FxPluginId.TAPE_SATURATION -> 22
        FxPluginId.PITCH_SHIFT -> 23
        FxPluginId.GAIN -> 24
        FxPluginId.ANALYZER -> 25
        FxPluginId.LOUDNESS_METER -> 26
        // Catalog-only entries (roadmap / offline-render-only DSP) have no
        // live native FX id yet; the engine treats 0 as "empty slot".
        FxPluginId.CABINET_SIM, FxPluginId.VOCODER, FxPluginId.GRANULAR_FX,
        FxPluginId.TIME_STRETCH -> 0
    }
