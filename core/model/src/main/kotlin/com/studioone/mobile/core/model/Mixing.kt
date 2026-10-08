package com.studioone.mobile.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class PanLaw(val dbCompensation: Float) {
    MINUS_3DB(-3f), MINUS_4_5DB(-4.5f), MINUS_6DB(-6f), ZERO(0f);
}

/** Send from a track to a bus/AUX return. */
@Serializable
data class Send(
    val targetBusId: BusId,
    val levelDb: Float = -Float.MAX_VALUE, // -inf default
    val preFader: Boolean = true,          // classic aux send behavior
    val enabled: Boolean = true,
    val pan: Float = 0f,
)

@Serializable
data class Bus(
    val id: BusId,
    val name: String,
    val kind: BusKind = BusKind.SUBGROUP,
    val volumeDb: Float = 0f,
    val pan: Float = 0f,
    val mute: Boolean = false,
    val inserts: List<FxSlot> = emptyList(),
    val outputBusId: BusId? = null,   // null => hardware out (master)
) {
    enum class BusKind { SUBGROUP, AUX_RETURN, MASTER }

    companion object {
        fun master() = Bus(BusId.MASTER, "Master", BusKind.MASTER)
        fun reverbReturn(index: Int = 1) = Bus(
            BusId("return_reverb_$index"), "Reverb $index", BusKind.AUX_RETURN,
        )
    }
}

/**
 * Meter snapshot pushed from the native engine at ~30Hz for the mixer UI.
 * Values are linear amplitude for peak/RMS, dB for loudness.
 */
data class MeterData(
    val busId: BusId,
    val peakLeft: Float,
    val peakRight: Float,
    val rmsLeft: Float,
    val rmsRight: Float,
    val clipFlagLeft: Boolean,
    val clipFlagRight: Boolean,
    val timestampNanos: Long,
)

/** ITU-R BS.1770 loudness snapshot for the master bus. */
data class LoudnessSnapshot(
    val momentaryLUFS: Float,   // 400ms gate
    val shortTermLUFS: Float,   // 3s gate
    val integratedLUFS: Float,  // whole-program (gated)
    val loudnessRangeLU: Float,
    val truePeakDbtp: Float,
)

/** Spectrum analyzer frame: magnitudes in dB across log-spaced bands. */
data class SpectrumFrame(
    val busId: BusId,
    val magnitudesDb: FloatArray,  // [bands] count, 20Hz..20kHz log spaced
    val binFrequenciesHz: FloatArray,
) {
    override fun equals(other: Any?): Boolean =
        other is SpectrumFrame && magnitudesDb.contentEquals(other.magnitudesDb)
    override fun hashCode(): Int = magnitudesDb.contentHashCode()
}

/** Everything the mixer screen needs for one channel strip. */
data class ChannelStripState(
    val trackId: TrackId,
    val name: String,
    val type: TrackType,
    val color: TrackColor,
    val volumeDb: Float,
    val pan: Float,
    val mute: Boolean,
    val solo: Boolean,
    val armed: Boolean,
    val inserts: List<FxSlot>,
    val sends: List<Send>,
    val input: InputRouting,
    val output: OutputRouting,
    val meter: MeterData?,
    val hasAutomationWrite: Boolean,
)
