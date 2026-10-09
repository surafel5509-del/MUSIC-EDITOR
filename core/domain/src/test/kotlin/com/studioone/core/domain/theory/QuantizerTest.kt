package com.studioone.core.domain.theory

import com.google.common.truth.Truth.assertThat
import com.studioone.core.domain.model.MidiNote
import com.studioone.core.domain.model.SnapDivision
import org.junit.Test

class QuantizerTest {

    private val sampleRate = 48_000
    private val bpm = 120.0
    // At 120 BPM a quarter note = 0.5s = 24000 frames.

    @Test
    fun `quantize snaps start to nearest 16th`() {
        val sixteenth = Quantizer.framesPerBeat(sampleRate, bpm) / 4 // 6000 frames
        val note = MidiNote(startFrame = sixteenth + 100, lengthFrames = 5_000, pitch = 60)
        val quantized = Quantizer.quantize(note, sampleRate, bpm, SnapDivision.BEAT_1_16)
        assertThat(quantized.startFrame).isEqualTo(sixteenth)
    }

    @Test
    fun `quantize preserves length by default`() {
        val note = MidiNote(startFrame = 12_345, lengthFrames = 5_000, pitch = 60)
        val quantized = Quantizer.quantize(note, sampleRate, bpm, SnapDivision.BEAT_1_4)
        assertThat(quantized.lengthFrames).isEqualTo(5_000)
    }

    @Test
    fun `off division leaves note untouched`() {
        val note = MidiNote(startFrame = 123, lengthFrames = 456, pitch = 60)
        assertThat(Quantizer.quantize(note, sampleRate, bpm, SnapDivision.OFF)).isEqualTo(note)
    }
}
