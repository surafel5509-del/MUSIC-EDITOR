package com.studioone.mobile.core.midi

import com.google.common.truth.Truth.assertThat
import com.studioone.mobile.core.model.ChordType
import com.studioone.mobile.core.model.MidiConstants
import com.studioone.mobile.core.model.MidiNote
import com.studioone.mobile.core.model.MusicalKey
import com.studioone.mobile.core.model.QuantizeOptions
import com.studioone.mobile.core.model.ScaleType
import com.studioone.mobile.core.model.SnapDivision
import org.junit.Test

class MidiQuantizerTest {

    private val sixteenth = MidiConstants.PPQ / 4 // 120 ticks

    private fun note(id: Long, start: Long, dur: Long = sixteenth.toLong(), key: Int = 60, vel: Int = 100) =
        MidiNote(id = id, startTick = start, durationTicks = dur, key = key, velocity = vel)

    @Test
    fun `quantize pulls early note to grid`() {
        val n = note(1, start = 100) // 20 ticks before the 120 grid
        val q = MidiQuantizer.quantizeNote(n, QuantizeOptions(SnapDivision.SIXTEENTH))
        assertThat(q.startTick).isEqualTo(120)
    }

    @Test
    fun `quantize pulls late note back to grid`() {
        val n = note(1, start = 170) // closer to 240? 170-120=50, 240-170=70 -> 120
        val q = MidiQuantizer.quantizeNote(n, QuantizeOptions(SnapDivision.SIXTEENTH))
        assertThat(q.startTick).isEqualTo(120)
    }

    @Test
    fun `quantize strength 50 percent moves halfway`() {
        val n = note(1, start = 100)
        val q = MidiQuantizer.quantizeNote(n, QuantizeOptions(SnapDivision.SIXTEENTH, strength = 0.5f))
        assertThat(q.startTick).isEqualTo(110) // halfway to 120
    }

    @Test
    fun `quantize strength 0 is identity`() {
        val n = note(1, start = 137)
        val q = MidiQuantizer.quantizeNote(n, QuantizeOptions(SnapDivision.SIXTEENTH, strength = 0f))
        assertThat(q.startTick).isEqualTo(137)
    }

    @Test
    fun `quantize end option resizes note to grid`() {
        val n = note(1, start = 0, dur = 130)
        val q = MidiQuantizer.quantizeNote(n, QuantizeOptions(SnapDivision.SIXTEENTH, quantizeNoteEnd = true))
        assertThat(q.durationTicks).isEqualTo(120)
    }

    @Test
    fun `swing delays off-beats proportionally`() {
        // Off-beat 16th at tick 120 with swing 0.5 shifts by 60 ticks.
        val swung = MidiQuantizer.applySwing(120, 120, 0.5f)
        assertThat(swung).isEqualTo(180)
        // On-beats never move.
        assertThat(MidiQuantizer.applySwing(240, 120, 0.5f)).isEqualTo(240)
    }

    @Test
    fun `division ticks match ppq math`() {
        assertThat(MidiQuantizer.divisionTicks(SnapDivision.QUARTER)).isEqualTo(480)
        assertThat(MidiQuantizer.divisionTicks(SnapDivision.EIGHTH_TRIPLET)).isEqualTo(160)
        assertThat(MidiQuantizer.divisionTicks(SnapDivision.NONE)).isEqualTo(0)
    }

    @Test
    fun `legato extends notes to next onset`() {
        val notes = listOf(
            note(1, 0, 50), note(2, 120, 50), note(3, 240, 50),
        )
        val legato = MidiQuantizer.legato(notes)
        assertThat(legato[0].durationTicks).isEqualTo(120)
        assertThat(legato[1].durationTicks).isEqualTo(120)
        assertThat(legato[2].durationTicks).isEqualTo(50) // last keeps length
    }

    @Test
    fun `strum staggers by offset`() {
        val chord = listOf(note(1, 0, key = 60), note(2, 0, key = 64), note(3, 0, key = 67))
        val strummed = MidiQuantizer.strum(chord, bpm = 120.0, offsetMs = 10.0)
        // 10ms at 120bpm = 0.02 beats = 9.6 ticks per step
        assertThat(strummed[0].startTick).isEqualTo(0)
        assertThat(strummed[1].startTick).isGreaterThan(strummed[0].startTick)
        assertThat(strummed[2].startTick).isGreaterThan(strummed[1].startTick)
    }

    @Test
    fun `split note produces two contiguous halves`() {
        val n = note(1, 0, 480)
        val halves = MidiQuantizer.split(n, 240)!!
        assertThat(halves.first.durationTicks).isEqualTo(240)
        assertThat(halves.second.startTick).isEqualTo(240)
        assertThat(halves.second.durationTicks).isEqualTo(240)
    }

    @Test
    fun `split outside note returns null`() {
        assertThat(MidiQuantizer.split(note(1, 100, 100), 50)).isNull()
        assertThat(MidiQuantizer.split(note(1, 100, 100), 250)).isNull()
    }

    @Test
    fun `classify C major triad`() {
        val chord = MidiQuantizer.classifyChord(setOf(0, 4, 7))
        assertThat(chord).isNotNull()
        assertThat(chord!!.rootPc).isEqualTo(0)
        assertThat(chord.type).isEqualTo(ChordType.MAJOR)
    }

    @Test
    fun `classify A minor seventh`() {
        val chord = MidiQuantizer.classifyChord(setOf(9, 0, 4, 7)) // A C E G
        assertThat(chord).isNotNull()
        assertThat(chord!!.type).isEqualTo(ChordType.MINOR7)
        assertThat(chord.rootPc).isEqualTo(9)
    }

    @Test
    fun `classify returns null for ambiguous cluster`() {
        assertThat(MidiQuantizer.classifyChord(setOf(0, 1))).isNull() // too few
    }

    @Test
    fun `snap to A minor pentatonic removes out-of-scale notes`() {
        val key = MusicalKey(9, ScaleType.MINOR_PENTATONIC) // A C D E G
        val notes = listOf(note(1, 0, key = 61), note(2, 120, key = 60), note(3, 240, key = 62))
        val snapped = MidiQuantizer.snapToScale(notes, key)
        assertThat(snapped.all { key.scale.contains(it.key % 12, key.tonicPc) }).isTrue()
        assertThat(snapped[1].key).isEqualTo(60) // C is in scale: unchanged
    }

    @Test
    fun `velocity ramp interpolates endpoints`() {
        val notes = (0 until 5).map { note(it.toLong(), it * 120L) }
        val ramped = MidiQuantizer.velocityRamp(notes, 40, 120)
        assertThat(ramped.first().velocity).isEqualTo(40)
        assertThat(ramped.last().velocity).isEqualTo(120)
        assertThat(ramped[2].velocity).isEqualTo(80)
    }

    @Test
    fun `humanize stays within bounds and is deterministic with seed`() {
        val notes = (0 until 20).map { note(it.toLong(), it * 120L, vel = 100) }
        val a = MidiQuantizer.humanize(notes, timingTicks = 10, velocityAmount = 5, seed = 7)
        val b = MidiQuantizer.humanize(notes, timingTicks = 10, velocityAmount = 5, seed = 7)
        assertThat(a).isEqualTo(b) // same seed => identical
        a.forEachIndexed { i, n ->
            assertThat(Math.abs(n.startTick - notes[i].startTick)).isAtMost(10)
            assertThat(Math.abs(n.velocity - 100)).isAtMost(5)
        }
    }
}
