package com.studioone.mobile.core.midi

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MidiParserTest {

    private val parser = MidiParser()

    @Test
    fun `parses note on message`() {
        assertThat(parser.feed(0x90)).isNull()
        assertThat(parser.feed(60)).isNull()
        val msg = parser.feed(100)
        assertThat(msg).isNotNull()
        assertThat(msg!!.type).isEqualTo(MessageType.NOTE_ON)
        assertThat(msg.data1).isEqualTo(60)
        assertThat(msg.data2).isEqualTo(100)
        assertThat(msg.channel).isEqualTo(0)
    }

    @Test
    fun `running status emits consecutive messages without status byte`() {
        parser.feed(0x91)
        parser.feed(64)
        val first = parser.feed(90)
        assertThat(first).isNotNull()
        // Next message: two data bytes only, status byte omitted (running status).
        assertThat(parser.feed(67)).isNull()
        val second = parser.feed(95)
        assertThat(second).isNotNull()
        assertThat(second!!.status).isEqualTo(0x91)
        assertThat(second.channel).isEqualTo(1)
        assertThat(second.data1).isEqualTo(67)
        assertThat(second.data2).isEqualTo(95)
    }

    @Test
    fun `program change is one data byte`() {
        parser.feed(0xC3)
        val msg = parser.feed(42)
        assertThat(msg).isNotNull()
        assertThat(msg!!.type).isEqualTo(MessageType.PROGRAM_CHANGE)
        assertThat(msg.data1).isEqualTo(42)
    }

    @Test
    fun `pitch bend combines 14-bit value`() {
        parser.feed(0xE0)
        parser.feed(0x00) // LSB
        val msg = parser.feed(0x40)!! // MSB = 64 => centered 8192
        assertThat(MidiMessage.bendValue(msg)).isEqualTo(8192)
        assertThat(MidiMessage.bendNormalized(msg)).isWithin(1e-4f).of(0f)
    }

    @Test
    fun `system realtime does not disturb running status`() {
        parser.feed(0x90); parser.feed(60)
        val clock = parser.feed(0xF8)
        assertThat(clock).isNotNull()
        assertThat(clock!!.status).isEqualTo(0xF8)
        val completed = parser.feed(100) // note-on data still under running status
        assertThat(completed).isNotNull()
        assertThat(completed!!.type).isEqualTo(MessageType.NOTE_ON)
    }

    @Test
    fun `orphan data bytes are ignored`() {
        val fresh = MidiParser()
        assertThat(fresh.feed(0x3C)).isNull()
        assertThat(fresh.feed(0x64)).isNull()
    }

    @Test
    fun `factory helpers roundtrip`() {
        val on = MidiMessage.noteOn(channel = 5, key = 72, velocity = 110, ts = 123)
        assertThat(on.status).isEqualTo(0x95)
        assertThat(on.channel).isEqualTo(5)
        val cc = MidiMessage.cc(2, CC.SUSTAIN, 127)
        assertThat(cc.type).isEqualTo(MessageType.CONTROL_CHANGE)
        assertThat(cc.data1).isEqualTo(64)
    }
}
