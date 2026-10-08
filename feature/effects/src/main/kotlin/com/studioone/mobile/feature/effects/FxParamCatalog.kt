package com.studioone.mobile.feature.effects

import com.studioone.mobile.core.model.FxParamSpec
import com.studioone.mobile.core.model.FxPluginId
import com.studioone.mobile.core.model.ParamTaper
import com.studioone.mobile.core.model.ParamUnit

/**
 * Parameter catalog for every native plugin. This is the UI contract paired
 * with the native param indices (fx/*.h namespaces like dynparam, revparam…).
 * Adding a plugin = native unit + catalog entry + FxPluginId enum; the
 * parameter editor renders itself from these specs.
 */
object FxParamCatalog {

    fun specs(plugin: FxPluginId): List<FxParamSpec> = when (plugin) {
        FxPluginId.COMPRESSOR, FxPluginId.EXPANDER -> listOf(
            FxParamSpec(0, "Threshold", -18f, -60f, 0f, ParamUnit.DB, ParamTaper.LINEAR),
            FxParamSpec(1, "Ratio", 3f, 1f, 20f, ParamUnit.RATIO),
            FxParamSpec(2, "Attack", 10f, 0.1f, 200f, ParamUnit.MS, ParamTaper.LOG),
            FxParamSpec(3, "Release", 120f, 10f, 1000f, ParamUnit.MS, ParamTaper.LOG),
            FxParamSpec(4, "Knee", 6f, 0f, 24f, ParamUnit.DB),
            FxParamSpec(5, "Makeup", 0f, 0f, 24f, ParamUnit.DB),
            FxParamSpec(6, "Mix", 1f, 0f, 1f, ParamUnit.PERCENT),
            FxParamSpec(7, "Detector", 0f, 0f, 1f),
            FxParamSpec(9, "Auto Release", 0f, 0f, 1f),
        )
        FxPluginId.LIMITER -> listOf(
            FxParamSpec(0, "Ceiling", -0.3f, -12f, 0f, ParamUnit.DB),
            FxParamSpec(1, "Release", 60f, 10f, 500f, ParamUnit.MS, ParamTaper.LOG),
        )
        FxPluginId.GATE -> listOf(
            FxParamSpec(0, "Threshold", -45f, -80f, 0f, ParamUnit.DB),
            FxParamSpec(1, "Attack", 1f, 0.1f, 50f, ParamUnit.MS, ParamTaper.LOG),
            FxParamSpec(2, "Hold", 40f, 0f, 500f, ParamUnit.MS),
            FxParamSpec(3, "Release", 150f, 10f, 1000f, ParamUnit.MS, ParamTaper.LOG),
            FxParamSpec(4, "Range", -60f, -90f, 0f, ParamUnit.DB),
        )
        FxPluginId.DE_ESSER -> listOf(
            FxParamSpec(0, "Frequency", 6500f, 2000f, 12000f, ParamUnit.HZ, ParamTaper.FREQUENCY),
            FxParamSpec(1, "Threshold", -30f, -60f, 0f, ParamUnit.DB),
            FxParamSpec(2, "Release", 80f, 10f, 400f, ParamUnit.MS),
        )
        FxPluginId.PARAMETRIC_EQ -> (0 until 8).flatMap { band ->
            listOf(
                FxParamSpec(band * 5 + 0, "Band ${band + 1} On", if (band < 3) 1f else 0f, 0f, 1f),
                FxParamSpec(band * 5 + 1, "Band ${band + 1} Type", eqTypeDefault(band), 0f, 7f),
                FxParamSpec(band * 5 + 2, "Band ${band + 1} Freq", eqFreqDefault(band), 20f, 20000f, ParamUnit.HZ, ParamTaper.FREQUENCY),
                FxParamSpec(band * 5 + 3, "Band ${band + 1} Q", 0.9f, 0.1f, 12f),
                FxParamSpec(band * 5 + 4, "Band ${band + 1} Gain", 0f, -18f, 18f, ParamUnit.DB),
            )
        }
        FxPluginId.HIGH_PASS, FxPluginId.LOW_PASS -> listOf(
            FxParamSpec(0, "Frequency", 80f, 20f, 20000f, ParamUnit.HZ, ParamTaper.FREQUENCY),
            FxParamSpec(1, "Resonance", 0.707f, 0.1f, 8f),
        )
        FxPluginId.REVERB, FxPluginId.PLATE_REVERB -> listOf(
            FxParamSpec(0, "Size", 0.7f, 0f, 1f, ParamUnit.PERCENT),
            FxParamSpec(1, "Damping", 0.5f, 0f, 1f, ParamUnit.PERCENT),
            FxParamSpec(2, "Width", 1f, 0f, 1f, ParamUnit.PERCENT),
            FxParamSpec(3, "Dry", 0f, -60f, 12f, ParamUnit.DB),
            FxParamSpec(4, "Wet", -12f, -60f, 12f, ParamUnit.DB),
            FxParamSpec(5, "Pre-Delay", 20f, 0f, 200f, ParamUnit.MS),
        )
        FxPluginId.DELAY, FxPluginId.PING_PONG_DELAY -> listOf(
            FxParamSpec(0, "Time", 375f, 5f, 2000f, ParamUnit.MS, ParamTaper.LOG),
            FxParamSpec(1, "Feedback", 0.35f, 0f, 0.95f, ParamUnit.PERCENT),
            FxParamSpec(2, "Wet", -9f, -60f, 12f, ParamUnit.DB),
            FxParamSpec(3, "Dry", 0f, -60f, 12f, ParamUnit.DB),
            FxParamSpec(4, "Low Cut", 120f, 20f, 1000f, ParamUnit.HZ, ParamTaper.FREQUENCY),
            FxParamSpec(5, "High Cut", 8000f, 1000f, 20000f, ParamUnit.HZ, ParamTaper.FREQUENCY),
            FxParamSpec(6, "Width", 1f, 0f, 1f, ParamUnit.PERCENT),
        )
        FxPluginId.CHORUS, FxPluginId.FLANGER -> listOf(
            FxParamSpec(0, "Rate", 0.8f, 0.05f, 10f, ParamUnit.HZ, ParamTaper.LOG),
            FxParamSpec(1, "Depth", 0.5f, 0f, 1f, ParamUnit.PERCENT),
            FxParamSpec(2, "Feedback", 0f, -0.9f, 0.9f, ParamUnit.PERCENT),
            FxParamSpec(3, "Mix", 0.5f, 0f, 1f, ParamUnit.PERCENT),
            FxParamSpec(4, "Center", 12f, 0.1f, 60f, ParamUnit.MS),
        )
        FxPluginId.PHASER -> listOf(
            FxParamSpec(0, "Rate", 0.5f, 0.02f, 8f, ParamUnit.HZ, ParamTaper.LOG),
            FxParamSpec(1, "Depth", 0.7f, 0f, 1f, ParamUnit.PERCENT),
            FxParamSpec(2, "Center Freq", 600f, 100f, 4000f, ParamUnit.HZ, ParamTaper.FREQUENCY),
            FxParamSpec(3, "Feedback", 0.3f, -0.9f, 0.9f, ParamUnit.PERCENT),
            FxParamSpec(4, "Mix", 0.5f, 0f, 1f, ParamUnit.PERCENT),
        )
        FxPluginId.TREMOLO, FxPluginId.AUTOPAN -> listOf(
            FxParamSpec(0, "Rate", 4f, 0.05f, 20f, ParamUnit.HZ, ParamTaper.LOG),
            FxParamSpec(1, "Depth", 0.5f, 0f, 1f, ParamUnit.PERCENT),
            FxParamSpec(2, "Pan Amount", 0f, 0f, 1f, ParamUnit.PERCENT),
        )
        FxPluginId.AUTO_FILTER -> listOf(
            FxParamSpec(0, "Rate", 1f, 0.02f, 16f, ParamUnit.HZ, ParamTaper.LOG),
            FxParamSpec(1, "Base Freq", 400f, 50f, 8000f, ParamUnit.HZ, ParamTaper.FREQUENCY),
            FxParamSpec(2, "Range (oct)", 3f, 0f, 6f, ParamUnit.RAW),
            FxParamSpec(3, "Resonance", 4f, 0.3f, 16f),
        )
        FxPluginId.DISTORTION, FxPluginId.OVERDRIVE, FxPluginId.AMP_SIM -> listOf(
            FxParamSpec(0, "Drive", 12f, 0f, 48f, ParamUnit.DB),
            FxParamSpec(1, "Tone", 0.5f, 0f, 1f, ParamUnit.PERCENT),
            FxParamSpec(2, "Level", 0f, -24f, 12f, ParamUnit.DB),
            FxParamSpec(3, "Shape", 0f, 0f, 3f),
        )
        FxPluginId.BITCRUSHER -> listOf(
            FxParamSpec(0, "Bits", 12f, 1f, 24f, ParamUnit.RAW),
            FxParamSpec(1, "Rate Div", 1f, 1f, 64f, ParamUnit.RAW),
            FxParamSpec(2, "Mix", 1f, 0f, 1f, ParamUnit.PERCENT),
        )
        FxPluginId.TAPE_SATURATION -> listOf(
            FxParamSpec(0, "Saturation", 0.4f, 0f, 1f, ParamUnit.PERCENT),
            FxParamSpec(1, "Wow/Flutter", 0.15f, 0f, 1f, ParamUnit.PERCENT),
            FxParamSpec(3, "Speed", 1f, 0f, 2f),
        )
        FxPluginId.PITCH_SHIFT -> listOf(
            FxParamSpec(0, "Semitones", 0f, -24f, 24f, ParamUnit.SEMITONES),
            FxParamSpec(1, "Stretch", 1f, 0.25f, 4f, ParamUnit.RATIO),
            FxParamSpec(2, "Grain", 80f, 20f, 200f, ParamUnit.MS),
        )
        FxPluginId.GAIN -> listOf(
            FxParamSpec(0, "Gain", 0f, -48f, 24f, ParamUnit.DB),
            FxParamSpec(1, "Width", 1f, 0f, 2f, ParamUnit.PERCENT),
            FxParamSpec(2, "Phase Invert", 0f, 0f, 1f),
        )
        else -> emptyList()
    }

    fun defaults(plugin: FxPluginId): Map<Int, Float> =
        specs(plugin).associate { it.index to it.defaultValue }

    private fun eqTypeDefault(band: Int): Float = when (band) {
        0 -> 6f  // HighShelf
        7 -> 5f  // LowShelf
        else -> 4f // Peak
    }

    private fun eqFreqDefault(band: Int): Float = when (band) {
        0 -> 80f; 1 -> 250f; 2 -> 750f; 3 -> 2200f
        4 -> 6000f; 5 -> 12000f; 6 -> 400f; else -> 16000f
    }
}
