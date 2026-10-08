package com.studioone.core.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TempoMapTest {

    private val map = TempoMap(sampleRate = 48_000, tempoMarks = listOf(TempoMark(0.0, 120.0)))

    @Test
    fun `beat and frame conversion round trips`() {
        val frame = map.frameAtBeat(4.0) // 4 beats @ 120bpm = 2 seconds
        assertThat(frame).isEqualTo(96_000)
        assertThat(map.beatAtFrame(frame)).isWithin(1e-9).of(4.0)
    }

    @Test
    fun `bars follow time signature`() {
        assertThat(map.barAtBeat(0.0)).isEqualTo(0)
        assertThat(map.barAtBeat(3.9)).isEqualTo(0)
        assertThat(map.barAtBeat(4.0)).isEqualTo(1)
    }

    @Test
    fun `snap lands on grid`() {
        val frame = map.frameAtBeat(1.1)
        val snapped = map.snapFrame(frame, SnapDivision.BEAT_1_4)
        assertThat(map.beatAtFrame(snapped)).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `tempo change shifts frame positions`() {
        val multi = TempoMap(
            sampleRate = 48_000,
            tempoMarks = listOf(TempoMark(0.0, 120.0), TempoMark(4.0, 60.0)),
        )
        // beat 5 = 4 beats @120 (2s) + 1 beat @60 (1s) = 3s = 144000 frames
        assertThat(multi.frameAtBeat(5.0)).isEqualTo(144_000)
    }
}
