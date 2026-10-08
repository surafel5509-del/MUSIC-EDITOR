package com.studioone.audio.analysis

import com.google.common.truth.Truth.assertThat
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Test

class BpmDetectorTest {

    /** Synthesizes a kick-like click train at the given BPM. */
    private fun clickTrack(bpm: Double, seconds: Int, sampleRate: Int = 44_100): FloatArray {
        val out = FloatArray(seconds * sampleRate)
        val beatFrames = (60.0 / bpm * sampleRate).toInt()
        var frame = 0
        while (frame < out.size) {
            // 20ms decaying noise burst as a stand-in for a kick transient.
            for (i in 0 until 880) {
                if (frame + i >= out.size) break
                val env = 1f - i / 880f
                out[frame + i] += (Math.random().toFloat() * 2f - 1f) * env * 0.8f
            }
            frame += beatFrames
        }
        return out
    }

    @Test
    fun `detects 120 bpm click track within 2 percent`() {
        val result = BpmDetector.detect(clickTrack(120.0, 8), 44_100)
        assertThat(result.bpm).isWithin(2.4).of(120.0)
        assertThat(result.confidence).isGreaterThan(0.0)
    }

    @Test
    fun `detects 90 bpm`() {
        val result = BpmDetector.detect(clickTrack(90.0, 8), 44_100)
        assertThat(result.bpm).isWithin(1.8).of(90.0)
    }

    @Test
    fun `too-short input returns zero`() {
        val result = BpmDetector.detect(FloatArray(44_100), 44_100)
        assertThat(result.bpm).isEqualTo(0.0)
    }
}
