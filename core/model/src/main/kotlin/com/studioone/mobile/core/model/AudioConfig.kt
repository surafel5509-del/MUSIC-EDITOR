package com.studioone.mobile.core.model

import kotlinx.serialization.Serializable

/** Supported engine sample rates. 44.1k/48k universal; 96k on capable devices. */
@Serializable
enum class EngineSampleRate(val hz: Int) { RATE_44_1(44_100), RATE_48(48_000), RATE_96(96_000) }

/** Buffer/frames-per-burst options. Smaller = lower latency, more CPU/underruns. */
@Serializable
enum class EngineBufferSize(val frames: Int) { B64(64), B128(128), B256(256), B512(512) }

/**
 * A physical audio device exposed by AAudio/OpenSL/USB. The engine picks the
 * best default; users override per-track in InputRouting.
 */
@Serializable
data class AudioDeviceInfo(
    val id: String,
    val name: String,
    val type: AudioDeviceType,
    val inputChannels: Int,
    val outputChannels: Int,
    val sampleRates: List<Int> = emptyList(),
    val isLowLatencyCapable: Boolean = false, // AAudio MMAP / fast mixer flag
    val isConnected: Boolean = true,
)

@Serializable
enum class AudioDeviceType { BUILTIN_MIC, BUILTIN_SPEAKER, WIRED_HEADSET, USB_AUDIO, BLUETOOTH_A2DP, BLUETOOTH_LE, HDMI }

/** User-facing recording/latency configuration (Settings > Audio). */
@Serializable
data class AudioSettings(
    val sampleRate: EngineSampleRate = EngineSampleRate.RATE_48,
    val bufferSize: EngineBufferSize = EngineBufferSize.B128,
    val recordingBitDepth: BitDepth = BitDepth.PCM_24,
    val inputDeviceId: String? = null,
    val outputDeviceId: String? = null,
    val lowLatencyMode: Boolean = true,     // AAudio PERFORMANCE_MODE_LOW_LATENCY / MMAP
    val exclusiveMode: Boolean = false,     // try MMAP exclusive
    val recordLatencyCompensationMs: Float = 0f, // manual offset, device quirks
    val metronomeDuringRecord: Boolean = true,
    val metronomeVolume: Float = 0.7f,
    val countInBars: Int = 0,               // 0 = off
    val bluetoothLatencyWarning: Boolean = true,
)

/** Runtime latency report surfaced in Settings and the record HUD. */
data class LatencyReport(
    val inputLatencyMs: Float,
    val outputLatencyMs: Float,
    val roundTripMs: Float,
    val bufferFrames: Int,
    val sampleRate: Int,
    val underrunCount: Long,
    val xrunCount: Long,
    val usingMmap: Boolean,
    val backendName: String, // "AAudio" | "OpenSL ES"
)

/** Transport state mirrored from the native engine. */
enum class TransportState { STOPPED, PLAYING, RECORDING, PAUSED, WINDING }

/** Record-mode specifics armed from the transport bar. */
@Serializable
data class RecordConfig(
    val mode: RecordMode = RecordMode.NORMAL,
    val punchInFrame: Long? = null,
    val punchOutFrame: Long? = null,
    val loopRecording: Boolean = false,
    val mergeTakes: Boolean = true,         // comping: keep takes as layers
    val countInBars: Int = 0,
    val recordFxMonitoring: Boolean = true, // apply input.monitorFxChain live
)

@Serializable
enum class RecordMode { NORMAL, PUNCH_IN_OUT, LOOP, OVERDUB, REPLACE }

/** Playback engine options (metronome, pre-roll). */
@Serializable
data class PlaybackConfig(
    val metronomeEnabled: Boolean = false,
    val loopEnabled: Boolean = false,
    val loopStartFrame: Long = 0,
    val loopEndFrame: Long = 0,
    val playbackRate: Float = 1f,           // varispeed for practice
)
