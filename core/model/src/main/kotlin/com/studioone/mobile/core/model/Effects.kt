package com.studioone.mobile.core.model

import kotlinx.serialization.Serializable

/**
 * Catalogue of first-party DSP plugins. Each id maps to a native factory in
 * the C++ engine (PluginRegistry::create). Third-party/plugin-host support
 * is designed around this same contract (docs/AUDIO_ENGINE.md §7).
 *
 * [tailMs] is the effect tail used for freeze/bounce padding and for
 * latency-compensated offline rendering.
 */
@Serializable
enum class FxPluginId(val displayName: String, val category: PluginCategory, val tailMs: Int = 0) {
    // Dynamics
    COMPRESSOR("Compressor", PluginCategory.DYNAMICS),
    LIMITER("Limiter", PluginCategory.DYNAMICS, tailMs = 10),
    GATE("Noise Gate", PluginCategory.DYNAMICS),
    EXPANDER("Expander", PluginCategory.DYNAMICS),
    DE_ESSER("De-Esser", PluginCategory.DYNAMICS),
    // EQ / Filter
    PARAMETRIC_EQ("Parametric EQ", PluginCategory.EQ),
    GRAPHIC_EQ("Graphic EQ", PluginCategory.EQ),
    HIGH_PASS("High-Pass Filter", PluginCategory.FILTER),
    LOW_PASS("Low-Pass Filter", PluginCategory.FILTER),
    AUTO_FILTER("Auto Filter", PluginCategory.FILTER),
    // Time-based
    REVERB("Studio Reverb", PluginCategory.REVERB, tailMs = 6000),
    PLATE_REVERB("Plate Reverb", PluginCategory.REVERB, tailMs = 4000),
    DELAY("Delay", PluginCategory.DELAY, tailMs = 3000),
    PING_PONG_DELAY("Ping-Pong Delay", PluginCategory.DELAY, tailMs = 3000),
    CHORUS("Chorus", PluginCategory.MODULATION, tailMs = 60),
    FLANGER("Flanger", PluginCategory.MODULATION, tailMs = 40),
    PHASER("Phaser", PluginCategory.MODULATION, tailMs = 20),
    TREMOLO("Tremolo", PluginCategory.MODULATION),
    AUTOPAN("Auto-Pan", PluginCategory.MODULATION),
    // Saturation / color
    DISTORTION("Distortion", PluginCategory.SATURATION),
    OVERDRIVE("Overdrive", PluginCategory.SATURATION),
    BITCRUSHER("Bitcrusher", PluginCategory.SATURATION),
    AMP_SIM("Amp Sim", PluginCategory.SATURATION),
    CABINET_SIM("Cabinet IR", PluginCategory.SATURATION, tailMs = 200),
    TAPE_SATURATION("Tape Saturation", PluginCategory.SATURATION),
    // Pitch / creative
    PITCH_SHIFT("Pitch Shift", PluginCategory.PITCH, tailMs = 50),
    TIME_STRETCH("Time Stretch", PluginCategory.PITCH, tailMs = 50),
    VOCODER("Vocoder", PluginCategory.CREATIVE, tailMs = 100),
    GRANULAR_FX("Granular Cloud", PluginCategory.CREATIVE, tailMs = 2000),
    // Utility
    GAIN("Gain", PluginCategory.UTILITY),
    PHASE_INVERT("Phase Invert", PluginCategory.UTILITY),
    MID_SIDE("Mid/Side", PluginCategory.UTILITY),
    ANALYZER("Spectrum Analyzer", PluginCategory.UTILITY),
    LOUDNESS_METER("Loudness Meter", PluginCategory.UTILITY),
}

@Serializable
enum class PluginCategory { DYNAMICS, EQ, FILTER, REVERB, DELAY, MODULATION, SATURATION, PITCH, CREATIVE, UTILITY, INSTRUMENT }

/** Parameter descriptor published by a plugin — drives auto-generated UI. */
@Serializable
data class FxParamSpec(
    val index: Int,
    val name: String,
    val defaultValue: Float,
    val min: Float,
    val max: Float,
    val unit: ParamUnit = ParamUnit.RAW,
    val taper: ParamTaper = ParamTaper.LINEAR,
    val automatable: Boolean = true,
)

@Serializable
enum class ParamUnit { RAW, DB, HZ, MS, PERCENT, SEMITONES, RATIO, DEGREES, BPM }
@Serializable
enum class ParamTaper { LINEAR, LOG, EXP, DECIBEL, FREQUENCY }

/** One instantiated effect in an insert chain. */
@Serializable
data class FxSlot(
    val id: FxSlotId,
    val plugin: FxPluginId,
    val params: Map<Int, Float> = emptyMap(), // param index -> value
    val enabled: Boolean = true,
    val wetMix: Float = 1f,                   // 0..1 dry/wet (parallel processing)
    val presetId: PresetId? = null,
    /** Plugin-reported latency in frames; summed up the chain for compensation. */
    val latencyFrames: Int = 0,
)

/** User-saved effect or instrument preset (local or cloud-shared). */
@Serializable
data class FxPreset(
    val id: PresetId,
    val plugin: FxPluginId?,          // null => full chain preset
    val name: String,
    val chain: List<FxSlot> = emptyList(),
    val params: Map<Int, Float> = emptyMap(),
    val authorId: UserId? = null,
    val isFactory: Boolean = true,
    val isFavorite: Boolean = false,
    val downloadCount: Int = 0,
    val tags: List<String> = emptyList(),
)

/** Mirrors native kMaxInsertSlots (common/Types.h). */
const val kMaxInsertSlotsUi = 8

/** A complete insert chain (used by tracks, buses, and master). */
@Serializable
data class FxChain(val slots: List<FxSlot> = emptyList()) {
    val totalLatencyFrames: Int get() = slots.sumOf { it.latencyFrames }
    val maxTailMs: Int get() = slots.maxOfOrNull { it.plugin.tailMs } ?: 0
}
