package com.studioone.midi

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MidiParserTest {

    @Test
    fun `parses note on and off`() {
        val parser = MidiParser()
        val events = parser.feed(byteArrayOf(0x90.toByte(), 60, 100, 0x80.toByte(), 60, 0))
        assertThat(events).hasSize(2)
        val on = events[0] as MidiEvent.NoteOn
        assertThat(on.pitch).isEqualTo(60)
        assertThat(on.velocity).isEqualTo(100)
        assertThat(events[1]).isInstanceOf(MidiEvent.NoteOff::class.java)
    }

    @Test
    fun `note on with zero velocity is note off`() {
        val parser = MidiParser()
        val events = parser.feed(byteArrayOf(0x90.toByte(), 60, 0))
        assertThat(events.single()).isInstanceOf(MidiEvent.NoteOff::class.java)
    }

    @Test
    fun `running status reuses previous status byte`() {
        val parser = MidiParser()
        val events = parser.feed(byteArrayOf(0x90.toByte(), 60, 100, 62, 90, 64, 80))
        assertThat(events).hasSize(3)
        assertThat(events.map { (it as MidiEvent.NoteOn).pitch }).containsExactly(60, 62, 64)
    }

    @Test
    fun `control change and pitch bend decode`() {
        val parser = MidiParser()
        val events = parser.feed(byteArrayOf(0xB0.toByte(), 7, 100, 0xE0.toByte(), 0x23, 0x61))
        val cc = events[0] as MidiEvent.ControlChange
        assertThat(cc.controller).isEqualTo(7)
        assertThat(cc.value).isEqualTo(100)
        val bend = events[1] as MidiEvent.PitchBend
        assertThat(bend.value).isEqualTo(0x23 or (0x61 shl 7))
    }

    @Test
    fun `sysex bytes are skipped without corrupting state`() {
        val parser = MidiParser()
        val events = parser.feed(byteArrayOf(0xF0.toByte(), 0x7E, 0x7F, 0xF7.toByte(), 0x90.toByte(), 60, 100))
        assertThat(events).hasSize(1)
        assertThat((events[0] as MidiEvent.NoteOn).pitch).isEqualTo(60)
    }
}
