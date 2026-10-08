package com.studioone.mobile.core.model

import kotlinx.serialization.Serializable

/**
 * Catalogue of built-in virtual instruments. Sample-based instruments load
 * packs from the loop library; synth instruments are fully generated in the
 * native engine (zero download needed for the core synth set).
 */
@Serializable
enum class InstrumentId(val displayName: String, val family: InstrumentFamily, val requiresPack: Boolean = false) {
    // Synths (native, no pack)
    SUBTRACTIVE_SYNTH("Volt-1 Subtractive", InstrumentFamily.SYNTH_SUBTRACTIVE),
    FM_SYNTH("Volt-FM", InstrumentFamily.SYNTH_FM),
    WAVETABLE_SYNTH("WaveForge", InstrumentFamily.SYNTH_WAVETABLE),
    GRANULAR_SYNTH("GrainField", InstrumentFamily.SYNTH_GRANULAR),
    BASS_SYNTH("LowEnd Theory", InstrumentFamily.SYNTH_SUBTRACTIVE),
    PAD_SYNTH("Aurora Pads", InstrumentFamily.SYNTH_WAVETABLE),
    // Drums
    DRUM_MACHINE("SP-16 Drum Machine", InstrumentFamily.DRUMS),
    DRUM_KIT_ACOUSTIC("Acoustic Kit", InstrumentFamily.DRUMS, requiresPack = true),
    DRUM_KIT_808("808 Kit", InstrumentFamily.DRUMS, requiresPack = true),
    DRUM_KIT_LIVE("Live Kit", InstrumentFamily.DRUMS, requiresPack = true),
    // Keys (sample packs)
    GRAND_PIANO("Grand Piano", InstrumentFamily.KEYS, requiresPack = true),
    ELECTRIC_PIANO("Rhodes-style EP", InstrumentFamily.KEYS, requiresPack = true),
    ORGAN("Tonewheel Organ", InstrumentFamily.KEYS, requiresPack = true),
    // Orchestral
    STRINGS("Strings Ensemble", InstrumentFamily.STRINGS, requiresPack = true),
    BRASS("Brass Section", InstrumentFamily.BRASS, requiresPack = true),
    PLUCKS("Plucks & Leads", InstrumentFamily.LEAD, requiresPack = true),
    // Sampler
    SAMPLER("Sampler (multisample)", InstrumentFamily.SAMPLER),
}

@Serializable
enum class InstrumentFamily(val displayName: String) {
    SYNTH_SUBTRACTIVE("Subtractive Synth"), SYNTH_FM("FM Synth"),
    SYNTH_WAVETABLE("Wavetable Synth"), SYNTH_GRANULAR("Granular Synth"),
    DRUMS("Drums"), KEYS("Keys"), STRINGS("Strings"),
    BRASS("Brass"), LEAD("Leads"), SAMPLER("Sampler"),
}

/**
 * An instrument bound to a track. Presets live in the pack/preset store;
 * macro values are normalized 0..1 and mapped per-preset by the native engine.
 */
@Serializable
data class InstrumentInstance(
    val instrument: InstrumentId,
    val presetId: PresetId,
    val macros: FloatArray = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f),
    val transposeOctaves: Int = 0,
    val fineTuneCents: Float = 0f,
    val velocityCurve: VelocityCurve = VelocityCurve.NORMAL,
    val polyphony: Int = 16,           // 1..32 voices (mid-range device budget: 16)
    val glideMs: Float = 0f,           // portamento
    val mpeEnabled: Boolean = false,
    val arpeggiator: ArpeggiatorConfig = ArpeggiatorConfig(),
    val stepPattern: StepPattern? = null, // drum machine only
    val outputGainDb: Float = 0f,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InstrumentInstance) return false
        return instrument == other.instrument && presetId == other.presetId &&
            macros.contentEquals(other.macros) && transposeOctaves == other.transposeOctaves &&
            fineTuneCents == other.fineTuneCents && velocityCurve == other.velocityCurve &&
            polyphony == other.polyphony && glideMs == other.glideMs && mpeEnabled == other.mpeEnabled &&
            arpeggiator == other.arpeggiator && stepPattern == other.stepPattern &&
            outputGainDb == other.outputGainDb
    }
    override fun hashCode(): Int = 31 * instrument.hashCode() + macros.contentHashCode()
}

@Serializable
enum class VelocityCurve { FIXED_LOW, FIXED_MED, FIXED_HIGH, SOFT, NORMAL, HARD }

/** A pad on the drum machine grid. */
@Serializable
data class DrumPad(
    val index: Int,
    val label: String,
    val sampleId: SampleId?,
    val key: Int = 36 + index,       // GM drum map default (C2 = kick)
    val chokeGroup: Int = -1,        // pads in same group cut each other (hat open/close)
    val gainDb: Float = 0f,
    val tuneCents: Float = 0f,
    val reverse: Boolean = false,
    val loop: Boolean = false,
    val sendsToMasterFx: Boolean = true,
)
