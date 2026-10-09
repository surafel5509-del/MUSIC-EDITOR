package com.studioone.midi

/** Decoded MIDI channel messages, timestamped in nanoseconds (uptime). */
sealed interface MidiEvent {
    val timestampNanos: Long

    data class NoteOn(val channel: Int, val pitch: Int, val velocity: Int, override val timestampNanos: Long) : MidiEvent
    data class NoteOff(val channel: Int, val pitch: Int, val velocity: Int, override val timestampNanos: Long) : MidiEvent
    data class ControlChange(val channel: Int, val controller: Int, val value: Int, override val timestampNanos: Long) : MidiEvent
    data class PitchBend(val channel: Int, val value: Int, override val timestampNanos: Long) : MidiEvent
    data class ProgramChange(val channel: Int, val program: Int, override val timestampNanos: Long) : MidiEvent
}

/**
 * Parses raw MIDI byte streams (USB/BLE) into [MidiEvent]s, including BLE
 * MIDI's timestamp framing. Pure and deterministic => unit-testable.
 */
class MidiParser {
    private var runningStatus = 0
    private var pending = ByteArray(3)
    private var pendingLength = 0
    private var expectedLength = 0

    /** Feeds bytes; emits zero or more events. */
    fun feed(data: ByteArray, length: Int = data.size, baseTimestampNanos: Long = 0L): List<MidiEvent> {
        val events = mutableListOf<MidiEvent>()
        var i = 0
        while (i < length) {
            val b = data[i].toInt() and 0xFF
            i++
            if (b and 0x80 != 0) {
                when {
                    b and 0xF0 == 0xF0 -> {
                        // System messages: skip sysex/realtime content entirely.
                        pendingLength = 0
                        expectedLength = 0
                        runningStatus = 0
                    }
                    else -> {
                        runningStatus = b
                        pendingLength = 0
                        expectedLength = when (b and 0xF0) {
                            0xC0, 0xD0 -> 1
                            else -> 2
                        }
                    }
                }
                continue
            }
            if (runningStatus == 0) continue
            pending[pendingLength++] = b.toByte()
            if (pendingLength >= expectedLength) {
                decode(runningStatus, pending, baseTimestampNanos)?.let(events::add)
                pendingLength = 0
            }
        }
        return events
    }

    private fun decode(status: Int, data: ByteArray, ts: Long): MidiEvent? {
        val channel = status and 0x0F
        val d1 = data[0].toInt() and 0x7F
        val d2 = if (data.size > 1) data[1].toInt() and 0x7F else 0
        return when (status and 0xF0) {
            0x90 -> if (d2 == 0) MidiEvent.NoteOff(channel, d1, 0, ts) else MidiEvent.NoteOn(channel, d1, d2, ts)
            0x80 -> MidiEvent.NoteOff(channel, d1, d2, ts)
            0xB0 -> MidiEvent.ControlChange(channel, d1, d2, ts)
            0xE0 -> MidiEvent.PitchBend(channel, d1 or (d2 shl 7), ts)
            0xC0 -> MidiEvent.ProgramChange(channel, d1, ts)
            else -> null
        }
    }
}
