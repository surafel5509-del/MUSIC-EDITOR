package com.studioone.core.domain.theory

import com.google.common.truth.Truth.assertThat
import com.studioone.core.domain.model.MidiNote
import org.junit.Test

class ArpeggiatorTest {

    private fun notes(vararg pitches: Int) =
        pitches.map { MidiNote(startFrame = 0, lengthFrames = 100, pitch = it) }

    @Test
    fun `up pattern cycles ascending`() {
        val arp = Arpeggiator(pattern = ArpPattern.UP, octaveRange = 1)
        val seq = arp.sequence(notes(60, 64, 67), stepFrames = 1000, totalSteps = 4)
        assertThat(seq.map { it.pitch }).containsExactly(60, 64, 67, 60).inOrder()
        assertThat(seq.first().startFrame).isEqualTo(0)
        assertThat(seq[1].startFrame).isEqualTo(1000)
    }

    @Test
    fun `octave range extends pool`() {
        val arp = Arpeggiator(pattern = ArpPattern.UP, octaveRange = 2)
        val seq = arp.sequence(notes(60, 67), stepFrames = 100, totalSteps = 4)
        assertThat(seq.map { it.pitch }).containsExactly(60, 67, 72, 79).inOrder()
    }

    @Test
    fun `gate percent controls note length`() {
        val arp = Arpeggiator(gatePercent = 0.5)
        val seq = arp.sequence(notes(60), stepFrames = 1000, totalSteps = 1)
        assertThat(seq.single().lengthFrames).isEqualTo(500)
    }
}
