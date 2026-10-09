package com.studioone.audio.analysis

import com.google.common.truth.Truth.assertThat
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Test

class KeyDetectorTest {

    /** Renders a chord as summed sines. */
    private fun chord(midiPitches: List<Int>, seconds: Int = 3, sampleRate: Int = 44_100): FloatArray {
        val out = FloatArray(seconds * sampleRate)
        for (midi in midiPitches) {
            val freq = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
            for (i in out.indices) {
                out[i] += (0.3 * sin(2.0 * PI * freq * i / sampleRate)).toFloat()
            }
        }
        return out
    }

    @Test
    fun `detects C major chord as C major`() {
        val result = KeyDetector.detect(chord(listOf(60, 64, 67)), 44_100) // C4 E4 G4
        assertThat(result.pitchClass).isEqualTo(0)
        assertThat(result.minor).isFalse()
    }

    @Test
    fun `detects A minor chord as minor`() {
        val result = KeyDetector.detect(chord(listOf(57, 60, 64)), 44_100) // A3 C4 E4
        assertThat(result.minor).isTrue()
    }
}
