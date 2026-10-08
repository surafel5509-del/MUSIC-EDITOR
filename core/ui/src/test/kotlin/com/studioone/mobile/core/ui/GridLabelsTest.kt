package com.studioone.mobile.core.ui

import com.google.common.truth.Truth.assertThat
import com.studioone.mobile.core.model.GridLabels
import org.junit.Test

class GridLabelsTest {

    private val labels = GridLabels(
        divisionsFrames = listOf(384_000, 192_000, 96_000, 48_000, 24_000, 12_000),
        barLengthFrames = 96_000,
    )

    @Test
    fun `bar starts are detected`() {
        assertThat(labels.isBarStart(0)).isTrue()
        assertThat(labels.isBarStart(96_000)).isTrue()
        assertThat(labels.isBarStart(48_000)).isFalse()
    }

    @Test
    fun `labels only render on labeled bars`() {
        assertThat(labels.labelFor(0)).isEqualTo("1")
        assertThat(labels.labelFor(96_000)).isEqualTo("2")
        assertThat(labels.labelFor(48_000)).isEmpty() // not a bar start
    }

    @Test
    fun `zero-length bars are handled defensively`() {
        val degenerate = GridLabels(emptyList(), 0)
        assertThat(degenerate.isBarStart(0)).isFalse()
        assertThat(degenerate.labelFor(0)).isEmpty()
    }
}
