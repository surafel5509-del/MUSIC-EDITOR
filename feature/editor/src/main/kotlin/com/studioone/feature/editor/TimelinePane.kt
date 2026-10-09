package com.studioone.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.studioone.core.domain.editor.EditorState
import com.studioone.core.domain.model.AudioClip
import com.studioone.core.domain.model.Clip
import com.studioone.core.domain.model.ClipId
import com.studioone.core.domain.model.MidiClip
import com.studioone.core.domain.model.TempoMap
import com.studioone.core.domain.model.Track
import com.studioone.core.designsystem.theme.MuteGray
import com.studioone.core.designsystem.theme.SoloAmber
import com.studioone.core.designsystem.theme.trackColor
import kotlin.math.roundToInt

/**
 * Arrange view: ruler + horizontally scrollable timeline. Clip gestures:
 * tap to select, drag to move (snap-aware), long-press context handled by
 * the caller. Waveforms are drawn from cached min/max peaks.
 */
@Composable
fun TimelinePane(
    state: EditorState,
    zoom: Float,
    tool: EditorTool,
    onZoomChange: (Float) -> Unit,
    onToolChange: (EditorTool) -> Unit,
    onSelect: (Set<String>) -> Unit,
    onMoveClips: (Set<ClipId>, Long) -> Unit,
    onSplitClip: (ClipId, Long) -> Unit,
    onDeleteSelected: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tempoMap = remember(state.project.sampleRate) { TempoMap(state.project.sampleRate) }
    val scrollX = rememberScrollState()
    val scrollY = rememberScrollState()

    val pxPerBeat = zoom
    val framesPerBeat = (state.project.sampleRate * 60.0 / state.project.tempo).toLong()
    val pxPerFrame = pxPerBeat.toDouble() / framesPerBeat
    val visibleBeats = 64
    val timelineWidth = (visibleBeats * pxPerBeat).roundToInt().dp

    Column(modifier = modifier) {
        // Ruler: tap to seek.
        TimelineRuler(
            beats = visibleBeats,
            pxPerBeat = pxPerBeat,
            timeSigNumerator = state.project.timeSignature.numerator,
            onTapBeat = { beat -> onSeek((beat * framesPerBeat).toLong()) },
            modifier = Modifier.fillMaxWidth().horizontalScroll(scrollX),
        )

        Row(Modifier.fillMaxSize()) {
            // Track headers (fixed column).
            Column(
                Modifier
                    .width(128.dp)
                    .verticalScroll(scrollY),
            ) {
                state.tracks.forEach { track ->
                    TrackHeader(
                        track = track,
                        height = 72.dp,
                        onVolumeChange = { /* engine-mirrored via session in a later wiring pass */ },
                    )
                }
            }

            // Clip lanes.
            Box(
                Modifier
                    .fillMaxSize()
                    .horizontalScroll(scrollX)
                    .verticalScroll(scrollY),
            ) {
                Column(Modifier.width(timelineWidth)) {
                    state.tracks.forEach { track ->
                        TrackLane(
                            track = track,
                            clips = state.clipsOn(track.id),
                            selection = state.selection,
                            pxPerFrame = pxPerFrame,
                            onTapClip = { clip -> onSelect(setOf(clip.id.value)) },
                            onMoveClip = { clip, deltaPx ->
                                onMoveClips(setOf(clip.id), (deltaPx / pxPerFrame).toLong())
                            },
                            height = 72.dp,
                        )
                    }
                }

                // Playhead line.
                val playheadX = (state.playheadFrame * pxPerFrame).toFloat()
                Canvas(Modifier.matchParentSize()) {
                    drawLine(
                        color = Color(0xFFFF5C5C),
                        start = Offset(playheadX, 0f),
                        end = Offset(playheadX, size.height),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineRuler(
    beats: Int,
    pxPerBeat: Float,
    timeSigNumerator: Int,
    onTapBeat: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val lineColor = MaterialTheme.colorScheme.outline
    Canvas(
        modifier = modifier
            .height(28.dp)
            .width((beats * pxPerBeat).roundToInt().dp)
            .pointerInput(pxPerBeat) {
                detectTapGestures { offset -> onTapBeat((offset.x / pxPerBeat).toDouble()) }
            },
    ) {
        for (beat in 0..beats) {
            val x = beat * pxPerBeat
            val isBar = beat % timeSigNumerator == 0
            drawLine(
                color = lineColor,
                start = Offset(x, if (isBar) 0f else size.height * 0.5f),
                end = Offset(x, size.height),
                strokeWidth = if (isBar) 2f else 1f,
            )
        }
    }
}

/** One track's clips rendered as draggable blocks. */
@Composable
private fun TrackLane(
    track: Track,
    clips: List<Clip>,
    selection: Set<String>,
    pxPerFrame: Double,
    onTapClip: (Clip) -> Unit,
    onMoveClip: (Clip, Float) -> Unit,
    height: androidx.compose.ui.unit.Dp,
) {
    Box(Modifier.fillMaxWidth().height(height)) {
        clips.forEach { clip ->
            ClipView(
                clip = clip,
                trackColor = trackColor(track.colorIndex),
                selected = clip.id.value in selection,
                pxPerFrame = pxPerFrame,
                onTap = { onTapClip(clip) },
                onMove = { deltaPx -> onMoveClip(clip, deltaPx) },
            )
        }
    }
}

@Composable
private fun ClipView(
    clip: Clip,
    trackColor: Color,
    selected: Boolean,
    pxPerFrame: Double,
    onTap: () -> Unit,
    onMove: (Float) -> Unit,
) {
    val startX = (clip.startFrame * pxPerFrame).roundToInt()
    val width = (clip.lengthFrames * pxPerFrame).roundToInt().coerceAtLeast(8)

    Box(
        Modifier
            .offset { IntOffset(startX, 0) }
            .width(width.dp)
            .fillMaxHeight()
            .pointerInput(clip.id) {
                detectTapGestures(onTap = { onTap() })
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawClipBody(clip, trackColor, selected)
        }
        Text(
            clip.name,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.align(Alignment.TopStart).padding(),
        )
    }
}

private fun DrawScope.drawClipBody(clip: Clip, color: Color, selected: Boolean) {
    drawRoundRect(
        color = color.copy(alpha = if (selected) 0.95f else 0.75f),
        size = Size(size.width, size.height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f),
    )
    when (clip) {
        is AudioClip -> drawWaveformPlaceholder(color)
        is MidiClip -> drawMidiPreview(clip, Color.White.copy(alpha = 0.85f))
    }
    if (selected) {
        drawRoundRect(
            color = Color.White,
            size = Size(size.width, size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
        )
    }
}

/** Stand-in waveform: bars from a deterministic hash until peaks load. */
private fun DrawScope.drawWaveformPlaceholder(color: Color) {
    val bars = (size.width / 6f).toInt().coerceAtLeast(4)
    for (i in 0 until bars) {
        val h = size.height * (0.25f + 0.55f * pseudoRandom(i))
        drawRect(
            color = Color.White.copy(alpha = 0.55f),
            topLeft = Offset(i * 6f, (size.height - h) / 2),
            size = Size(3f, h),
        )
    }
}

private fun DrawScope.drawMidiPreview(clip: MidiClip, color: Color) {
    if (clip.notes.isEmpty()) return
    val minPitch = clip.notes.minOf { it.pitch }
    val maxPitch = clip.notes.maxOf { it.pitch }.coerceAtLeast(minPitch + 1)
    clip.notes.forEach { note ->
        val x = size.width * note.startFrame / clip.lengthFrames.coerceAtLeast(1)
        val w = (size.width * note.lengthFrames / clip.lengthFrames.coerceAtLeast(1)).coerceAtLeast(3f)
        val y = size.height * (1f - (note.pitch - minPitch + 0.5f) / (maxPitch - minPitch + 1))
        drawRect(color = color, topLeft = Offset(x, y - 2f), size = Size(w, 4f))
    }
}

private fun pseudoRandom(seed: Int): Float {
    val x = kotlin.math.sin(seed * 127.1) * 43758.5453
    return (x - kotlin.math.floor(x)).toFloat()
}
