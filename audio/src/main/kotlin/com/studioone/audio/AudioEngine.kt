package com.studioone.audio

import android.util.Log
import com.studioone.core.domain.model.EffectType

/**
 * Kotlin facade over the C++ audio engine (JNI).
 *
 * Threading:
 *  - topology mutations (addTrack/addClip/addEffect) must happen while the
 *    engine is idle or between renders; the wrapper funnels them through a
 *    single-thread dispatcher owned by the caller ([AudioEngineController]),
 *  - parameter/transport/midi/meter calls are lock-free and safe anywhere.
 */
class AudioEngine {

    private var handle: Long = 0L

    val isNativeReady: Boolean get() = handle != 0L

    fun create() {
        if (handle == 0L) handle = nativeCreate()
    }

    fun start(config: AudioEngineConfig): Boolean {
        if (handle == 0L) create()
        return nativeStart(
            handle,
            config.sampleRate,
            config.bufferSize,
            config.inputEnabled,
            config.useLowLatency,
            config.forceOpenSlesFallback,
        )
    }

    fun stop() {
        if (handle != 0L) nativeStop(handle)
    }

    fun destroy() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    // ---- Transport ---------------------------------------------------------

    fun setTransportState(state: TransportState) = nativeSetTransportState(handle, state.value)
    fun seek(frame: Long) = nativeSeek(handle, frame)
    fun playhead(): Long = nativeGetPlayhead(handle)
    fun setLoop(enabled: Boolean, startFrame: Long, endFrame: Long) =
        nativeSetLoop(handle, enabled, startFrame, endFrame)
    fun setTempo(bpm: Double) = nativeSetTempo(handle, bpm)
    fun setMetronome(enabled: Boolean) = nativeSetMetronome(handle, enabled)

    // ---- Graph -------------------------------------------------------------

    fun addTrack(withInstrument: Boolean, instrumentKind: InstrumentKind): Int =
        nativeAddTrack(handle, withInstrument, instrumentKind.ordinal)

    fun removeTrack(trackId: Int) = nativeRemoveTrack(handle, trackId)

    fun setTrackBasic(trackId: Int, gain: Float, pan: Float, mute: Boolean, solo: Boolean, armed: Boolean) =
        nativeSetTrackBasic(handle, trackId, gain, pan, mute, solo, armed)

    /** Loads a WAV file and attaches it as a clip. Returns false on decode failure. */
    fun addAudioClip(
        trackId: Int,
        wavPath: String,
        clipStartFrame: Long,
        sourceOffsetFrames: Long = 0,
        gain: Float = 1f,
        reversed: Boolean = false,
    ): Boolean = nativeAddAudioClip(handle, trackId, wavPath, clipStartFrame, sourceOffsetFrames, gain, reversed)

    fun clearClips(trackId: Int) = nativeClearClips(handle, trackId)

    fun sendMidi(trackId: Int, status: Int, data1: Int, data2: Int) =
        nativeSendMidi(handle, trackId, status, data1, data2)

    fun noteOn(trackId: Int, pitch: Int, velocity: Int) = sendMidi(trackId, 0x90, pitch, velocity)
    fun noteOff(trackId: Int, pitch: Int) = sendMidi(trackId, 0x80, pitch, 0)

    /** Appends an effect to a node's insert chain. Returns false for unknown types. */
    fun addEffect(nodeId: Int, type: EffectType): Boolean =
        nativeAddEffect(handle, nodeId, type.ordinal)

    /**
     * Sets an effect parameter. [insertIndex] selects the slot in the chain;
     * [paramIndex] matches the EffectCatalog parameter order.
     */
    fun setEffectParameter(nodeId: Int, insertIndex: Int, paramIndex: Int, value: Float) =
        nativeSetParameter(handle, nodeId, (insertIndex shl 8) or (paramIndex and 0xFF), value)

    // ---- Recording -----------------------------------------------------------

    fun startRecording(path: String, bitDepth: Int): Boolean = nativeStartRecording(handle, path, bitDepth)
    fun stopRecording() = nativeStopRecording(handle)

    // ---- Meters --------------------------------------------------------------

    /** Returns [peakL, peakR, rmsL, rmsR, lufsMomentary, lufsIntegrated, clipL, clipR]. */
    fun readMasterMeter(): FloatArray {
        val out = FloatArray(8)
        nativeReadMasterMeter(handle, out)
        return out
    }

    fun readTrackMeter(trackId: Int): FloatArray {
        val out = FloatArray(8)
        nativeReadTrackMeter(handle, trackId, out)
        return out
    }

    /** Engine-reported output latency in milliseconds (Oboe calculation). */
    fun outputLatencyMs(): Double = if (handle != 0L) nativeOutputLatencyMs(handle) else 0.0
    fun framesPerBurst(): Int = if (handle != 0L) nativeFramesPerBurst(handle) else 0

    // ---- JNI -------------------------------------------------------------------

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeStart(
        handle: Long, sampleRate: Int, bufferSize: Int,
        inputEnabled: Boolean, useLowLatency: Boolean, forceOpenSles: Boolean,
    ): Boolean
    private external fun nativeStop(handle: Long)
    private external fun nativeOutputLatencyMs(handle: Long): Double
    private external fun nativeFramesPerBurst(handle: Long): Int
    private external fun nativeSetTransportState(handle: Long, state: Int)
    private external fun nativeSeek(handle: Long, frame: Long)
    private external fun nativeGetPlayhead(handle: Long): Long
    private external fun nativeSetLoop(handle: Long, enabled: Boolean, start: Long, end: Long)
    private external fun nativeSetTempo(handle: Long, bpm: Double)
    private external fun nativeSetMetronome(handle: Long, enabled: Boolean)
    private external fun nativeAddTrack(handle: Long, withInstrument: Boolean, instrumentKind: Int): Int
    private external fun nativeRemoveTrack(handle: Long, trackId: Int)
    private external fun nativeSetTrackBasic(
        handle: Long, trackId: Int, gain: Float, pan: Float,
        mute: Boolean, solo: Boolean, armed: Boolean,
    )
    private external fun nativeAddAudioClip(
        handle: Long, trackId: Int, path: String,
        clipStartFrame: Long, sourceOffsetFrames: Long, gain: Float, reversed: Boolean,
    ): Boolean
    private external fun nativeClearClips(handle: Long, trackId: Int)
    private external fun nativeSendMidi(handle: Long, trackId: Int, status: Int, data1: Int, data2: Int)
    private external fun nativeAddEffect(handle: Long, nodeId: Int, effectKind: Int): Boolean
    private external fun nativeSetParameter(handle: Long, nodeId: Int, paramId: Int, value: Float)
    private external fun nativeStartRecording(handle: Long, path: String, bitDepth: Int): Boolean
    private external fun nativeStopRecording(handle: Long)
    private external fun nativeReadMasterMeter(handle: Long, out: FloatArray)
    private external fun nativeReadTrackMeter(handle: Long, trackId: Int, out: FloatArray)

    companion object {
        private const val TAG = "AudioEngine"
        private val loadResult: Result<Unit> = runCatching { System.loadLibrary("studioone-audio") }

        val isNativeLibraryAvailable: Boolean
            get() = loadResult.isSuccess

        init {
            loadResult.onFailure { Log.e(TAG, "Native library failed to load", it) }
        }
    }
}

enum class TransportState(val value: Int) { STOPPED(0), PLAYING(1), RECORDING(2) }

/** Instrument kinds matching MidiInstrumentSource::Kind in C++. */
enum class InstrumentKind { SUBTRACTIVE_SYNTH, SAMPLER, DRUM_SYNTH }

data class AudioEngineConfig(
    val sampleRate: Int = 44_100,
    val bufferSize: Int = 128,
    val inputEnabled: Boolean = false,
    val useLowLatency: Boolean = true,
    val forceOpenSlesFallback: Boolean = false,
)
