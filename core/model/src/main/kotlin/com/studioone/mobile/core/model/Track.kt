package com.studioone.mobile.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class TrackType {
    AUDIO,          // recorded / imported audio
    MIDI,           // MIDI clip track driving an external or internal instrument
    INSTRUMENT,     // MIDI + embedded virtual instrument (renders audio internally)
    BUS,            // subgroup
    AUX_RETURN,     // fx return (reverb/delay sends land here)
    MASTER,         // the single master chain
}

@Serializable
enum class TrackColor(val argb: Int) {
    CORAL(0xFFE57373.toInt()), AMBER(0xFFFFB74D.toInt()), LIME(0xFFAED581.toInt()),
    TEAL(0xFF4DB6AC.toInt()), SKY(0xFF4FC3F7.toInt()), INDIGO(0xFF7986CB.toInt()),
    ORCHID(0xFFBA68C8.toInt()), ROSE(0xFFF06292.toInt()), SLATE(0xFF90A4AE.toInt());
}

/** Per-track recording / monitoring configuration. */
@Serializable
data class InputRouting(
    val deviceAddress: String? = null,     // null = default input of selected device
    val inputChannel: Int = 0,             // index within the device
    val armed: Boolean = false,            // record-enable ("arm")
    val monitorMode: MonitorMode = MonitorMode.AUTO,
    val inputGainDb: Float = 0f,
    /** Inserted monitoring FX rendered only during record (low-latency path). */
    val monitorFxChain: List<FxSlot> = emptyList(),
)

@Serializable
enum class MonitorMode { OFF, ON, AUTO /* on while armed & not playing back own take */ }

@Serializable
data class OutputRouting(
    val busId: BusId = BusId.MASTER,
    val channels: List<Int> = listOf(0, 1), // mapping into the bus
) {
    companion object { val DEFAULT = OutputRouting() }
}

/**
 * A track in the arranger. Tracks own an ordered list of clips and automation
 * lanes, plus mixer state. Keep this class cheap to copy: the arranger diffing
 * and CRDT layers copy tracks on every edit.
 */
@Serializable
data class Track(
    val id: TrackId,
    val name: String,
    val type: TrackType = TrackType.AUDIO,
    val color: TrackColor = TrackColor.SKY,
    val orderIndex: Int = 0,
    val clips: List<Clip> = emptyList(),
    val volumeDb: Float = 0f,             // fader position, dB (range -inf..+12)
    val pan: Float = 0f,                  // -1..1 (constant-power law, see native PanNode)
    val mute: Boolean = false,
    val solo: Boolean = false,
    val frozen: Boolean = false,          // frozen => renders to bounce file, FX bypassed live
    val frozenFileUri: String? = null,
    val input: InputRouting = InputRouting(),
    val output: OutputRouting = OutputRouting.DEFAULT,
    val inserts: List<FxSlot> = emptyList(),
    val sends: List<Send> = emptyList(),
    val automation: List<AutomationLane> = emptyList(),
    val instrument: InstrumentInstance? = null, // non-null for INSTRUMENT/MIDI tracks
    val width: Float = 1f,                // stereo width multiplier
    val phaseInvert: Boolean = false,
    val visible: Boolean = true,
    val heightCells: Int = 2,             // arranger row height in grid cells
    val latencyCompensationSamples: Int = 0, // computed from insert chain; see docs/AUDIO_ENGINE.md
) {
    /** Clips overlapping a time range, used for punch/loop recording region queries. */
    fun clipsInRange(startFrame: Long, endFrame: Long): List<Clip> =
        clips.filter { it.startFrame < endFrame && it.startFrame + it.lengthFrames > startFrame }
}
