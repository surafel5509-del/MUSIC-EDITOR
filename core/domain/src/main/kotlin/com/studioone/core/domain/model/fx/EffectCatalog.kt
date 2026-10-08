package com.studioone.core.domain.model.fx

import com.studioone.core.domain.model.EffectType

/** Declares one automatable parameter of an effect or instrument. */
data class ParamSpec(
    val id: String,
    val name: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val unit: String = "",
    val logarithmic: Boolean = false,
)

/**
 * Metadata catalog for every built-in effect. Used to render parameter UIs,
 * build native effect instances and validate presets.
 */
object EffectCatalog {

    val specs: Map<EffectType, List<ParamSpec>> = mapOf(
        EffectType.PARAMETRIC_EQ to listOf(
            ParamSpec("hp_freq", "HP Freq", 20f, 2000f, 20f, "Hz", logarithmic = true),
            ParamSpec("band1_freq", "Band 1 Freq", 20f, 20_000f, 250f, "Hz", logarithmic = true),
            ParamSpec("band1_gain", "Band 1 Gain", -18f, 18f, 0f, "dB"),
            ParamSpec("band1_q", "Band 1 Q", 0.1f, 18f, 0.9f),
            ParamSpec("band2_freq", "Band 2 Freq", 20f, 20_000f, 1_200f, "Hz", logarithmic = true),
            ParamSpec("band2_gain", "Band 2 Gain", -18f, 18f, 0f, "dB"),
            ParamSpec("band2_q", "Band 2 Q", 0.1f, 18f, 1.0f),
            ParamSpec("band3_freq", "Band 3 Freq", 20f, 20_000f, 6_000f, "Hz", logarithmic = true),
            ParamSpec("band3_gain", "Band 3 Gain", -18f, 18f, 0f, "dB"),
            ParamSpec("band3_q", "Band 3 Q", 0.1f, 18f, 1.0f),
            ParamSpec("lp_freq", "LP Freq", 200f, 20_000f, 20_000f, "Hz", logarithmic = true),
        ),
        EffectType.COMPRESSOR to listOf(
            ParamSpec("threshold", "Threshold", -60f, 0f, -18f, "dB"),
            ParamSpec("ratio", "Ratio", 1f, 20f, 4f, ":1"),
            ParamSpec("attack_ms", "Attack", 0.1f, 200f, 10f, "ms", logarithmic = true),
            ParamSpec("release_ms", "Release", 10f, 1000f, 120f, "ms", logarithmic = true),
            ParamSpec("knee_db", "Knee", 0f, 24f, 6f, "dB"),
            ParamSpec("makeup_db", "Makeup", 0f, 24f, 0f, "dB"),
            ParamSpec("mix", "Mix", 0f, 1f, 1f),
        ),
        EffectType.LIMITER to listOf(
            ParamSpec("ceiling_db", "Ceiling", -24f, 0f, -1f, "dB"),
            ParamSpec("release_ms", "Release", 10f, 500f, 100f, "ms"),
            ParamSpec("lookahead_ms", "Lookahead", 0f, 10f, 2f, "ms"),
        ),
        EffectType.NOISE_GATE to listOf(
            ParamSpec("threshold_db", "Threshold", -90f, 0f, -50f, "dB"),
            ParamSpec("attack_ms", "Attack", 0.1f, 50f, 2f, "ms"),
            ParamSpec("release_ms", "Release", 10f, 1000f, 150f, "ms"),
            ParamSpec("hysteresis_db", "Hysteresis", 0f, 12f, 4f, "dB"),
        ),
        EffectType.REVERB to listOf(
            ParamSpec("decay", "Decay", 0.1f, 12f, 2.2f, "s"),
            ParamSpec("predelay_ms", "Pre-Delay", 0f, 200f, 20f, "ms"),
            ParamSpec("damping", "Damping", 200f, 16_000f, 6_000f, "Hz", logarithmic = true),
            ParamSpec("size", "Size", 0f, 1f, 0.7f),
            ParamSpec("mix", "Mix", 0f, 1f, 0.3f),
        ),
        EffectType.DELAY to listOf(
            ParamSpec("time_ms", "Time", 1f, 2000f, 375f, "ms", logarithmic = true),
            ParamSpec("feedback", "Feedback", 0f, 0.95f, 0.35f),
            ParamSpec("damping", "Damping", 200f, 18_000f, 8_000f, "Hz", logarithmic = true),
            ParamSpec("mix", "Mix", 0f, 1f, 0.25f),
            ParamSpec("sync", "Sync", 0f, 1f, 0f),
        ),
        EffectType.CHORUS to listOf(
            ParamSpec("rate_hz", "Rate", 0.05f, 8f, 0.8f, "Hz"),
            ParamSpec("depth_ms", "Depth", 0.1f, 12f, 2.5f, "ms"),
            ParamSpec("mix", "Mix", 0f, 1f, 0.5f),
        ),
        EffectType.FLANGER to listOf(
            ParamSpec("rate_hz", "Rate", 0.05f, 6f, 0.4f, "Hz"),
            ParamSpec("depth", "Depth", 0f, 1f, 0.7f),
            ParamSpec("feedback", "Feedback", -0.95f, 0.95f, 0.3f),
            ParamSpec("mix", "Mix", 0f, 1f, 0.5f),
        ),
        EffectType.PHASER to listOf(
            ParamSpec("rate_hz", "Rate", 0.05f, 8f, 0.6f, "Hz"),
            ParamSpec("depth", "Depth", 0f, 1f, 0.8f),
            ParamSpec("stages", "Stages", 2f, 12f, 4f),
            ParamSpec("feedback", "Feedback", 0f, 0.9f, 0.4f),
        ),
        EffectType.TREMOLO to listOf(
            ParamSpec("rate_hz", "Rate", 0.1f, 20f, 5f, "Hz"),
            ParamSpec("depth", "Depth", 0f, 1f, 0.8f),
            ParamSpec("waveform", "Wave", 0f, 3f, 0f),
        ),
        EffectType.DISTORTION to listOf(
            ParamSpec("drive_db", "Drive", 0f, 48f, 18f, "dB"),
            ParamSpec("tone", "Tone", 200f, 12_000f, 3_500f, "Hz", logarithmic = true),
            ParamSpec("mix", "Mix", 0f, 1f, 1f),
        ),
        EffectType.OVERDRIVE to listOf(
            ParamSpec("drive_db", "Drive", 0f, 36f, 12f, "dB"),
            ParamSpec("tone", "Tone", 200f, 8_000f, 2_500f, "Hz", logarithmic = true),
            ParamSpec("level", "Level", 0f, 2f, 1f),
        ),
        EffectType.BITCRUSHER to listOf(
            ParamSpec("bits", "Bits", 1f, 24f, 12f, "bit"),
            ParamSpec("downsample", "Downsample", 1f, 40f, 1f, "x"),
        ),
        EffectType.AMP_SIM to listOf(
            ParamSpec("gain", "Gain", 0f, 1f, 0.5f),
            ParamSpec("model", "Model", 0f, 3f, 0f),
            ParamSpec("tone", "Tone", 0f, 1f, 0.5f),
            ParamSpec("level", "Level", 0f, 2f, 1f),
        ),
        EffectType.CABINET_SIM to listOf(
            ParamSpec("model", "Cabinet", 0f, 3f, 0f),
            ParamSpec("mic_position", "Mic Pos", 0f, 1f, 0.5f),
        ),
        EffectType.FILTER to listOf(
            ParamSpec("freq", "Cutoff", 20f, 20_000f, 1_000f, "Hz", logarithmic = true),
            ParamSpec("resonance", "Resonance", 0.1f, 20f, 0.707f),
            ParamSpec("type", "Type", 0f, 3f, 0f),
        ),
        EffectType.AUTOFILTER to listOf(
            ParamSpec("rate_hz", "Rate", 0.05f, 10f, 1f, "Hz"),
            ParamSpec("depth", "Depth", 0f, 1f, 0.8f),
            ParamSpec("freq", "Base Cutoff", 20f, 20_000f, 800f, "Hz", logarithmic = true),
        ),
        EffectType.PITCH_SHIFT to listOf(
            ParamSpec("semitones", "Semitones", -24f, 24f, 0f, "st"),
            ParamSpec("cents", "Cents", -100f, 100f, 0f, "ct"),
            ParamSpec("mix", "Mix", 0f, 1f, 1f),
        ),
        EffectType.TIME_STRETCH to listOf(
            ParamSpec("rate", "Rate", 0.25f, 4f, 1f, "x"),
        ),
        EffectType.VOCODER to listOf(
            ParamSpec("bands", "Bands", 4f, 32f, 16f),
            ParamSpec("attack_ms", "Attack", 1f, 100f, 20f, "ms"),
            ParamSpec("release_ms", "Release", 10f, 1000f, 200f, "ms"),
        ),
        EffectType.GRAPHIC_EQ to listOf(
            ParamSpec("gain_31", "31 Hz", -12f, 12f, 0f, "dB"),
            ParamSpec("gain_62", "62 Hz", -12f, 12f, 0f, "dB"),
            ParamSpec("gain_125", "125 Hz", -12f, 12f, 0f, "dB"),
            ParamSpec("gain_250", "250 Hz", -12f, 12f, 0f, "dB"),
            ParamSpec("gain_500", "500 Hz", -12f, 12f, 0f, "dB"),
            ParamSpec("gain_1k", "1 kHz", -12f, 12f, 0f, "dB"),
            ParamSpec("gain_2k", "2 kHz", -12f, 12f, 0f, "dB"),
            ParamSpec("gain_4k", "4 kHz", -12f, 12f, 0f, "dB"),
            ParamSpec("gain_8k", "8 kHz", -12f, 12f, 0f, "dB"),
            ParamSpec("gain_16k", "16 kHz", -12f, 12f, 0f, "dB"),
        ),
        EffectType.EXPANDER to EffectCatalogPlaceholder.EXPANDER,
        EffectType.DE_ESSER to listOf(
            ParamSpec("threshold_db", "Threshold", -60f, 0f, -30f, "dB"),
            ParamSpec("freq", "Frequency", 2_000f, 12_000f, 6_500f, "Hz", logarithmic = true),
            ParamSpec("range_db", "Range", 0f, 40f, 20f, "dB"),
        ),
    )

    /** Default values for an effect type, used when instantiating inserts. */
    fun defaults(type: EffectType): Map<String, Float> =
        specs[type]?.associate { it.id to it.default } ?: emptyMap()

    private object EffectCatalogPlaceholder {
        val EXPANDER = listOf(
            ParamSpec("threshold_db", "Threshold", -90f, 0f, -45f, "dB"),
            ParamSpec("ratio", "Ratio", 1f, 8f, 2f, ":1"),
            ParamSpec("attack_ms", "Attack", 0.1f, 100f, 5f, "ms"),
            ParamSpec("release_ms", "Release", 10f, 1000f, 100f, "ms"),
        )
    }
}
