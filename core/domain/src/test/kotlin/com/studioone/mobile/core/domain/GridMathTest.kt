package com.studioone.mobile.core.domain

import com.google.common.truth.Truth.assertThat
import com.studioone.mobile.core.model.MidiConstants
import com.studioone.mobile.core.model.SnapDivision
import com.studioone.mobile.core.model.TempoMap
import com.studioone.mobile.core.model.TempoMarker
import com.studioone.mobile.core.model.TimeSignature
import org.junit.Test

class GridMathTest {

    private val grid44 = GridMath(TempoMap(), TimeSignature(4, 4), sampleRate = 48_000)

    @Test
    fun `one beat at 120bpm 48k is 24000 frames`() {
        assertThat(grid44.ticksToFrames(MidiConstants.PPQ.toLong())).isEqualTo(24_000L)
    }

    @Test
    fun `one bar equals four beats in 4-4`() {
        assertThat(grid44.barsToFrames(1.0)).isEqualTo(96_000L)
    }

    @Test
    fun `frames to ticks inverts ticks to frames`() {
        for (tick in listOf(0L, 120L, 480L, 1920L, 12_345L, 100_000L)) {
            val frames = grid44.ticksToFrames(tick)
            assertThat(grid44.framesToTicks(frames)).isAtLeast(tick - 1)
            assertThat(grid44.framesToTicks(frames)).isAtMost(tick + 1)
        }
    }

    @Test
    fun `3-4 time signature bar length is three beats`() {
        val grid34 = GridMath(TempoMap(), TimeSignature(3, 4), sampleRate = 48_000)
        assertThat(grid34.barsToFrames(1.0)).isEqualTo(72_000L)
    }

    @Test
    fun `tempo change doubles frame cost after marker`() {
        val map = TempoMap(
            markers = listOf(
                TempoMarker(bar = 0.0, bpm = 120.0),
                TempoMarker(bar = 4.0, bpm = 60.0), // half tempo => twice the frames/beat
            ),
        )
        val grid = GridMath(map, TimeSignature(4, 4), 48_000)
        // Bar 0..4 at 120bpm: 4 * 96000 = 384000 frames.
        val startOfBar4 = grid.barsToFrames(4.0)
        assertThat(startOfBar4).isEqualTo(384_000L)
        // One beat after the marker at 60bpm = 48000 frames (vs 24000 before).
        val bar5 = grid.barsToFrames(5.0)
        assertThat(bar5 - startOfBar4).isEqualTo(192_000L) // 4 beats * 48000
    }

    @Test
    fun `snap rounds to nearest sixteenth`() {
        val sixteenth = grid44.ticksToFrames(MidiConstants.PPQ.toLong() / 4) // 6000 frames
        assertThat(grid44.snapFrames(sixteenth + 100, SnapDivision.SIXTEENTH)).isEqualTo(sixteenth)
        assertThat(grid44.snapFrames(sixteenth * 2 - 100, SnapDivision.SIXTEENTH)).isEqualTo(sixteenth * 2)
    }

    @Test
    fun `snap with zero strength is identity`() {
        assertThat(grid44.snapFrames(12_345, SnapDivision.SIXTEENTH, strength = 0f)).isEqualTo(12_345L)
    }

    @Test
    fun `beat frames produces one position per beat`() {
        val beats = grid44.beatFrames(0, 96_000)
        assertThat(beats).containsExactly(0L, 24_000L, 48_000L, 72_000L, 96_000L).inOrder()
    }
}
