package com.studioone.core.domain.model

import com.google.common.truth.Truth.assertThat
import com.studioone.core.domain.model.music.Chords
import com.studioone.core.domain.model.music.MusicalKey
import org.junit.Test

class ChordsTest {

    @Test
    fun `C major triad pitches`() {
        assertThat(Chords.major(0).pitches(48)).containsExactly(48, 52, 55)
    }

    @Test
    fun `diatonic triads in C major`() {
        val chords = Chords.diatonicTriads(MusicalKey(root = 0, scale = "MAJOR"))
        assertThat(chords.map { it.symbol }).containsExactly(
            "C", "Dm", "Em", "F", "G", "Am", "Bdim",
        ).inOrder()
    }
}
