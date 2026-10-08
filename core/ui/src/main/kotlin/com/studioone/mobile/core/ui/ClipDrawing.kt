package com.studioone.mobile.core.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.studioone.mobile.core.model.AudioClip
import com.studioone.mobile.core.model.MidiClip
import com.studioone.mobile.core.model.WaveformPeaks
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * DrawScope extensions shared by the arranger lanes and the loop-browser
 * preview cards. All drawing is allocation-light (single Path reuse per call
 * is acceptable — Compose pools Paths internally per draw pass).
 */

/** Waveform envelope for an audio clip within its lane rect. */
fun DrawScope.drawWaveformForClip(
    clip: AudioClip,
    clipX: Float,
    clipWidth: Float,
    laneHeight: Float,
    pixelsPerFrame: Float,
    firstVisibleFrame: Long,
    color: Color,
) {
    val peaks: WaveformPeaks = clip.fileRef.peaks ?: run {
        // Not analyzed yet: flat placeholder band.
        drawRoundRect(
            color = color.copy(alpha = 0.35f),
            topLeft = Offset(clipX + 3f, laneHeight * 0.42f),
            size = androidx.compose.ui.geometry.Size(max(2f, clipWidth - 6f), laneHeight * 0.16f),
            cornerRadius = CornerRadius(4f),
        )
        return
    }
    val midY = laneHeight / 2f
    val framesPerPx = 1f / pixelsPerFrame
    val stepPx = 2 // one envelope vertex every 2px keeps overdraw bounded
    val path = Path()
    var first = true
    var px = 0f
    val srcDuration = clip.fileRef.durationFrames.coerceAtLeast(1)
    while (px < clipWidth) {
        val frameInClip = ((clipX + px - clipX) * framesPerPx).toLong()
        val srcFrame = clip.sourceOffsetFrames + frameInClip % srcDuration
        val bucket = (srcFrame / peaks.framesPerBucket).toInt()
            .coerceIn(0, peaks.bucketCount - 1)
        var minV = 1f; var maxV = -1f
        for (ch in 0 until peaks.channelCount) {
            minV = min(minV, peaks.minAt(bucket, ch))
            maxV = max(maxV, peaks.maxAt(bucket, ch))
        }
        val fade = clipFadeScale(frameInClip, clip)
        val topY = midY - maxV * (midY - 3f) * fade
        val botY = midY - minV * (midY - 3f) * fade
        // Draw as vertical spans (classic waveform look, no path fill needed).
        drawLine(
            color = color.copy(alpha = if (clip.muted) 0.3f else 0.9f),
            start = Offset(clipX + px, topY),
            end = Offset(clipX + px, botY),
            strokeWidth = stepPx.toFloat(),
        )
        first = false
        px += stepPx
    }
    path.close()
}

private fun clipFadeScale(frameInClip: Long, clip: AudioClip): Float {
    val fadeIn = clip.fade.fadeInFrames
    val fadeOut = clip.fade.fadeOutFrames
    if (fadeIn > 0 && frameInClip < fadeIn) return sqrt(frameInClip.toFloat() / fadeIn)
    if (fadeOut > 0 && frameInClip > clip.lengthFrames - fadeOut) {
        val t = (clip.lengthFrames - frameInClip).toFloat() / fadeOut
        return sqrt(max(0f, min(1f, t)))
    }
    return 1f
}

/** Piano-roll style note preview inside a MIDI clip lane. */
fun DrawScope.drawMidiNotesPreview(
    clip: MidiClip,
    clipX: Float,
    clipWidth: Float,
    laneHeight: Float,
    pixelsPerFrame: Float,
    firstVisibleFrame: Long,
    color: Color = Color(0xFF8FA6FF),
) {
    if (clip.notes.isEmpty()) return
    val minKey = clip.notes.minOf { it.key }
    val maxKey = clip.notes.maxOf { it.key }
    val keyRange = max(1, maxKey - minKey)
    val clipStartFrame = clip.startFrame
    val framesPerPx = 1f / pixelsPerFrame
    for (note in clip.notes) {
        // Notes are in ticks; approximate pixel mapping via clip length ratio
        // (the piano roll does exact tick mapping; the lane preview is a sketch).
        val totalTicks = max(1, clip.notes.maxOf { it.startTick + it.durationTicks })
        val x = clipX + (note.startTick.toFloat() / totalTicks) * clipWidth
        val w = max(2f, (note.durationTicks.toFloat() / totalTicks) * clipWidth)
        val y = laneHeight - ((note.key - minKey).toFloat() / keyRange) * (laneHeight - 8f) - 4f
        if (x > clipX + clipWidth || x + w < clipX) continue
        drawRoundRect(
            color = color.copy(alpha = if (note.muted) 0.25f else 0.9f),
            topLeft = Offset(x, y - 2f),
            size = androidx.compose.ui.geometry.Size(w, 4f),
            cornerRadius = CornerRadius(2f),
        )
    }
}
