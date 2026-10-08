package com.studioone.mobile.core.midi

/**
 * MIDI 1.0 message model + wire parsing.
 *
 * Covers channel voice messages, system realtime (clock/start/stop/continue),
 * and MPE interpretation (per-channel bend/pressure/timbre on channels 1-15).
 * Running status is handled by [MidiParser].
 */
data class MidiMessage(
    val timestampNanos: Long,
    val status: Int,      // status byte (0x80..0xFF)
    val data1: Int = 0,
    val data2: Int = 0,
) {
    val type: MessageType get() = MessageType.of(status and 0xF0)
    val channel: Int get() = status and 0x0F

    companion object {
        fun noteOn(channel: Int, key: Int, velocity: Int, ts: Long = 0) =
            MidiMessage(ts, 0x90 or channel, key, velocity)
        fun noteOff(channel: Int, key: Int, velocity: Int = 64, ts: Long = 0) =
            MidiMessage(ts, 0x80 or channel, key, velocity)
        fun cc(channel: Int, controller: Int, value: Int, ts: Long = 0) =
            MidiMessage(ts, 0xB0 or channel, controller, value)
        fun pitchBend(channel: Int, value14: Int, ts: Long = 0) =
            MidiMessage(ts, 0xE0 or channel, value14 and 0x7F, (value14 shr 7) and 0x7F)
        fun programChange(channel: Int, program: Int, ts: Long = 0) =
            MidiMessage(ts, 0xC0 or channel, program)
        fun pressure(channel: Int, value: Int, ts: Long = 0) =
            MidiMessage(ts, 0xD0 or channel, value)
        /** 14-bit bend value centered at 8192. */
        fun bendValue(msg: MidiMessage): Int = (msg.data2 shl 7) or msg.data1
        fun bendNormalized(msg: MidiMessage): Float = (bendValue(msg) - 8192) / 8192f
    }
}

enum class MessageType(val statusCode: Int) {
    NOTE_OFF(0x80), NOTE_ON(0x90), POLY_PRESSURE(0xA0),
    CONTROL_CHANGE(0xB0), PROGRAM_CHANGE(0xC0), CHANNEL_PRESSURE(0xD0),
    PITCH_BEND(0xE0), SYSTEM(0xF0);

    companion object {
        fun of(statusHighNibble: Int): MessageType =
            entries.firstOrNull { it.statusCode == statusHighNibble } ?: SYSTEM
    }
}

/** Standard CC numbers used across the app. */
object CC {
    const val MOD_WHEEL = 1
    const val BREATH = 2
    const val FOOT = 4
    const val PORTAMENTO_TIME = 5
    const val DATA_ENTRY_MSB = 6
    const val VOLUME = 7
    const val PAN = 10
    const val EXPRESSION = 11
    const val SUSTAIN = 64
    const val PORTAMENTO = 65
    const val SOSTENUTO = 66
    const val SOFT_PEDAL = 67
    const val ALL_SOUND_OFF = 120
    const val RESET_ALL = 121
    const val ALL_NOTES_OFF = 123
    // MPE zone configuration
    const val MPE_TIMBRE = 74
    const val RPN_LSB = 100
    const val RPN_MSB = 101
}

/**
 * Streaming MIDI byte parser with running status. Feed it raw bytes from any
 * transport (USB, BLE, TCP); it emits complete [MidiMessage]s.
 */
class MidiParser {
    private var runningStatus = 0
    private var pending = IntArray(2)
    private var pendingCount = 0
    private var expectedBytes = 0

    /** Parse one byte; returns a message when complete. */
    fun feed(byte: Int, timestampNanos: Long = System.nanoTime()): MidiMessage? {
        val b = byte and 0xFF
        if (b >= 0xF8) {
            // System realtime: single byte, does not disturb running status.
            return MidiMessage(timestampNanos, b)
        }
        if (b >= 0x80) {
            runningStatus = b
            pendingCount = 0
            expectedBytes = when (b and 0xF0) {
                0xC0, 0xD0 -> 1
                0xF0 -> when (b) { 0xF1, 0xF3 -> 1; 0xF2 -> 2; else -> 0 }
                else -> 2
            }
            if (expectedBytes == 0) {
                return MidiMessage(timestampNanos, b)
            }
            return null
        }
        // Data byte.
        if (runningStatus == 0) return null // orphan data byte
        pending[pendingCount++] = b
        if (pendingCount >= expectedBytes) {
            val msg = MidiMessage(
                timestampNanos, runningStatus,
                pending.getOrElse(0) { 0 }, pending.getOrElse(1) { 0 },
            )
            pendingCount = 0 // running status: keep status for the next message
            return msg
        }
        return null
    }

    fun reset() {
        runningStatus = 0
        pendingCount = 0
    }
}
