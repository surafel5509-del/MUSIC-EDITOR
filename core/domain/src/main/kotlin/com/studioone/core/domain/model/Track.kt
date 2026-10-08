package com.studioone.core.domain.model

/** Kind of content a track carries. */
enum class TrackType { AUDIO, MIDI }

/** Routing endpoint for track outputs. */
enum class OutputBus(val id: String) {
    MASTER("master"),
    DRUM_BUS("bus-drums"),
    VOCAL_BUS("bus-vocals"),
    FX_BUS_REVERB("bus-fx-reverb"),
    FX_BUS_DELAY("bus-fx-delay"),
}

/**
 * A track inside a project. Audio state that must reach the native engine in
 * real time (gain, pan, mute) is mirrored to the engine via parameter FIFOs;
 * the values here remain the source of truth on the Kotlin side.
 */
data class Track(
    val id: TrackId,
    val projectId: ProjectId,
    val name: String,
    val type: TrackType,
    val colorIndex: Int = 0,
    val orderIndex: Int = 0,
    val volume: Float = 0.8f,
    val pan: Float = 0f,
    val muted: Boolean = false,
    val soloed: Boolean = false,
    val armed: Boolean = false,
    val frozen: Boolean = false,
    val inputId: String = "default-mic",
    val outputBus: OutputBus = OutputBus.MASTER,
    val instrumentId: String? = null,
    val inserts: List<EffectInstance> = emptyList(),
    val sends: List<SendInstance> = emptyList(),
    val automation: List<AutomationLane> = emptyList(),
) {
    /** Effective gain considering solo logic is applied by the mixer, not here. */
    fun withVolume(v: Float) = copy(volume = v.coerceIn(0f, 4f))
}

/** An effect instance sitting in a track/bus insert chain. */
@kotlinx.serialization.Serializable
data class EffectInstance(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: EffectType,
    val presetName: String? = null,
    val enabled: Boolean = true,
    val params: Map<String, Float> = emptyMap(),
    val orderIndex: Int = 0,
)

/** Post-fader send from a track to an FX bus. */
@kotlinx.serialization.Serializable
data class SendInstance(
    val id: String = java.util.UUID.randomUUID().toString(),
    val targetBus: OutputBus,
    val level: Float = 0f,
    val preFader: Boolean = false,
)

/** Every built-in effect type. The native engine maps these to DSP classes. */
enum class EffectType(val displayName: String, val category: EffectCategory) {
    PARAMETRIC_EQ("Parametric EQ", EffectCategory.EQ),
    GRAPHIC_EQ("Graphic EQ", EffectCategory.EQ),
    COMPRESSOR("Compressor", EffectCategory.DYNAMICS),
    LIMITER("Limiter", EffectCategory.DYNAMICS),
    NOISE_GATE("Noise Gate", EffectCategory.DYNAMICS),
    EXPANDER("Expander", EffectCategory.DYNAMICS),
    DE_ESSER("De-Esser", EffectCategory.DYNAMICS),
    REVERB("Reverb", EffectCategory.TIME),
    DELAY("Delay", EffectCategory.TIME),
    CHORUS("Chorus", EffectCategory.MODULATION),
    FLANGER("Flanger", EffectCategory.MODULATION),
    PHASER("Phaser", EffectCategory.MODULATION),
    TREMOLO("Tremolo", EffectCategory.MODULATION),
    AUTOFILTER("Auto Filter", EffectCategory.MODULATION),
    DISTORTION("Distortion", EffectCategory.SATURATION),
    OVERDRIVE("Overdrive", EffectCategory.SATURATION),
    BITCRUSHER("Bitcrusher", EffectCategory.SATURATION),
    AMP_SIM("Amp Simulator", EffectCategory.SATURATION),
    CABINET_SIM("Cabinet Simulator", EffectCategory.SATURATION),
    FILTER("Filter", EffectCategory.FILTER),
    PITCH_SHIFT("Pitch Shift", EffectCategory.PITCH),
    TIME_STRETCH("Time Stretch", EffectCategory.PITCH),
    VOCODER("Vocoder", EffectCategory.SPECIAL),
}

enum class EffectCategory { EQ, DYNAMICS, TIME, MODULATION, SATURATION, FILTER, PITCH, SPECIAL }

/** Automation targets a lane can drive. */
enum class AutomationTarget { VOLUME, PAN, EFFECT_PARAM, SEND_LEVEL, TEMPO, PITCH }

enum class AutomationCurve { LINEAR, EXPONENTIAL, S_CURVE }

@kotlinx.serialization.Serializable
data class AutomationPoint(
    val frame: Long,
    val value: Float,
    val curve: AutomationCurve = AutomationCurve.LINEAR,
)

@kotlinx.serialization.Serializable
data class AutomationLane(
    val id: String = java.util.UUID.randomUUID().toString(),
    val trackId: TrackId,
    val target: AutomationTarget,
    val effectInstanceId: String? = null,
    val paramId: String? = null,
    val points: List<AutomationPoint> = emptyList(),
)
