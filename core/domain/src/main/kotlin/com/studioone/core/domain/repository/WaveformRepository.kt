package com.studioone.core.domain.repository

/**
 * Cached waveform peak data used by the timeline to draw clips at any zoom
 * level without re-decoding audio. Peaks are min/max pairs per bucket.
 */
interface WaveformRepository {
    /** Returns peaks for [sourcePath] bucketed to [bucketCount] min/max pairs. */
    suspend fun getPeaks(sourcePath: String, bucketCount: Int, offsetFrames: Long, lengthFrames: Long): FloatArray

    /** Invalidates cache when a file is re-recorded or re-imported. */
    suspend fun invalidate(sourcePath: String)
}
