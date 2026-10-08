package com.studioone.mobile.feature.arranger

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studioone.mobile.core.designsystem.components.S1RecordButton
import com.studioone.mobile.core.designsystem.theme.NumericReadout
import com.studioone.mobile.core.designsystem.theme.NumericReadoutLarge
import com.studioone.mobile.core.designsystem.theme.S1Colors
import com.studioone.mobile.core.designsystem.theme.S1Shapes
import com.studioone.mobile.core.model.Clip
import com.studioone.mobile.core.model.ClipId
import com.studioone.mobile.core.model.GridLabels
import com.studioone.mobile.core.model.MidiClip
import com.studioone.mobile.core.model.Track
import com.studioone.mobile.core.model.TrackType
import com.studioone.mobile.core.model.TransportState
import com.studioone.mobile.core.ui.TimelineRuler
import com.studioone.mobile.core.ui.drawMidiNotesPreview
import com.studioone.mobile.core.ui.drawWaveformForClip
import kotlin.math.roundToInt

/**
 * The arranger: track headers + timeline + transport.
 *
 * Layout is a fixed two-column split (headers | lanes) with the lanes sharing
 * one horizontal scroll offset and zoom level. Gesture model:
 *  * one-finger horizontal drag on lanes: scroll
 *  * pinch: zoom around the gesture center (anchor-stable)
 *  * tap ruler: seek playhead
 *  * drag ruler edge: set loop region (long-press ruler toggles loop)
 *  * tap clip: select; drag clip (SELECT tool): move with snap
 *  * double-tap clip: open editor (piano roll for MIDI, clip inspector for audio)
 *  * SPLIT tool + tap: split at tapped frame
 *
 * Rendering: each track lane is a Canvas drawing only the clips that
 * intersect the visible frame window (culling), from pre-computed peaks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArrangerScreen(
    onOpenPianoRoll: (trackId: String, clipId: String) -> Unit,
    onOpenMixer: () -> Unit,
    onOpenFxRack: (trackId: String) -> Unit,
    onOpenLoops: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArrangerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val project = state.project ?: return
    var showAddTrackSheet by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val trackRowHeight = with(density) { 84.dp.toPx() }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ArrangerTopBar(
            positionLabel = state.positionLabel,
            tool = state.tool,
            onToolChange = viewModel::setTool,
            canUndo = state.canUndo,
            canRedo = state.canRedo,
            onUndo = viewModel::undo,
            onRedo = viewModel::redo,
            onOpenMixer = onOpenMixer,
            onBack = onBack,
        )

        // ── Ruler ────────────────────────────────────────────────────────────
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(96.dp)) // aligns with track headers
            Box(Modifier.weight(1f).height(36.dp)) {
                val gridLabels = remember(project) {
                    val grid = com.studioone.mobile.core.domain.GridMath(
                        project.tempoMap, project.timeSignature, project.sampleRate)
                    GridLabels(
                        divisionsFrames = listOf(8, 4, 2, 1, 0.5, 0.25).map {
                            grid.barsToFrames(it)
                        }.sortedDescending().distinct(),
                        barLengthFrames = grid.barsToFrames(1.0),
                        sampleRate = project.sampleRate,
                    )
                }
                TimelineRuler(
                    pixelsPerFrame = state.pixelsPerFrame,
                    scrollOffsetFrames = state.scrollFrames,
                    gridLabels = gridLabels,
                    loopStartFrame = if (state.loopEnabled) state.loopStartFrame else null,
                    loopEndFrame = if (state.loopEnabled) state.loopEndFrame else null,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(state.pixelsPerFrame, state.scrollFrames) {
                            detectTapGestures { offset ->
                                val frame = state.scrollFrames + (offset.x / state.pixelsPerFrame).toLong()
                                viewModel.seek(frame)
                            }
                        },
                )
                // Playhead in ruler.
                PlayheadLine(
                    frame = state.playheadFrame,
                    pixelsPerFrame = state.pixelsPerFrame,
                    scrollFrames = state.scrollFrames,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ── Tracks ───────────────────────────────────────────────────────────
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val visibleWidthPx = with(density) { maxWidth.toPx() }
            val firstVisibleFrame = state.scrollFrames
            val lastVisibleFrame = state.scrollFrames + (visibleWidthPx / state.pixelsPerFrame).toLong()

            Row(Modifier.fillMaxSize()) {
                // Track headers.
                LazyColumn(
                    Modifier.width(96.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(project.tracks, key = { it.id.value }) { track ->
                        TrackHeader(
                            track = track,
                            heightDp = 84.dp,
                            onMute = { viewModel.toggleTrackMute(track.id) },
                            onSolo = { viewModel.toggleTrackSolo(track.id) },
                            onArm = { viewModel.toggleTrackArm(track.id) },
                            onOpenFx = { onOpenFxRack(track.id.value) },
                            selected = (state.selection as? Selection.Track)?.trackId == track.id ||
                                (state.selection as? Selection.Clip)?.trackId == track.id,
                            onSelect = { viewModel.select(Selection.Track(track.id)) },
                        )
                    }
                }

                // Clip lanes with zoom + scroll + drag.
                val transformState = rememberTransformableState { zoomChange, panChange, _ ->
                    viewModel.onZoom(zoomChange, state.scrollFrames + (visibleWidthPx / 2 / state.pixelsPerFrame).toLong())
                    viewModel.onScroll(-(panChange.x / state.pixelsPerFrame).toLong())
                }
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .transformable(transformState)
                        .pointerInput(state.pixelsPerFrame) {
                            detectHorizontalDragGestures { _, dragAmount ->
                                viewModel.onScroll(-(dragAmount / state.pixelsPerFrame).toLong())
                            }
                        },
                ) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
                        items(project.tracks, key = { it.id.value }) { track ->
                            TrackLane(
                                track = track,
                                heightPx = trackRowHeight,
                                pixelsPerFrame = state.pixelsPerFrame,
                                firstVisibleFrame = firstVisibleFrame,
                                lastVisibleFrame = lastVisibleFrame,
                                tool = state.tool,
                                selection = state.selection,
                                onSelectClip = { clipId -> viewModel.select(Selection.Clip(track.id, clipId)) },
                                onMoveClip = { clipId, frame -> viewModel.moveSelectionTo(frame) },
                                onSplitAt = { frame ->
                                    viewModel.select(Selection.Clip(track.id,
                                        track.clips.firstOrNull { frame in it.startFrame until it.endFrame }?.id
                                            ?: ClipId("")) )
                                    viewModel.splitAtPlayhead()
                                },
                                onOpenClip = { clip ->
                                    if (clip is MidiClip) onOpenPianoRoll(track.id.value, clip.id.value)
                                },
                                modifier = Modifier.fillMaxWidth().height(84.dp),
                            )
                        }
                    }
                    // Playhead overlay above all lanes.
                    PlayheadLine(
                        frame = state.playheadFrame,
                        pixelsPerFrame = state.pixelsPerFrame,
                        scrollFrames = state.scrollFrames,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Loop overlay.
                    if (state.loopEnabled) {
                        LoopOverlay(
                            startFrame = state.loopStartFrame,
                            endFrame = state.loopEndFrame,
                            pixelsPerFrame = state.pixelsPerFrame,
                            scrollFrames = state.scrollFrames,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        // ── Transport ────────────────────────────────────────────────────────
        TransportBar(
            state = state,
            onPlay = viewModel::play,
            onStop = viewModel::stop,
            onPause = viewModel::pause,
            onRecord = viewModel::startRecording,
            onToggleMetronome = viewModel::toggleMetronome,
            onToggleLoop = viewModel::toggleLoop,
            onAddTrack = { showAddTrackSheet = true },
            onOpenLoops = onOpenLoops,
        )

        // Recording HUD overlay.
        state.recordingHud?.let { hud ->
            RecordingHudBar(hud)
        }
    }

    if (showAddTrackSheet) {
        ModalBottomSheet(onDismissRequest = { showAddTrackSheet = false }) {
            Column(Modifier.padding(bottom = 32.dp)) {
                Text("Add track", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                listOf(
                    TrackType.AUDIO to "Audio — record or import",
                    TrackType.INSTRUMENT to "Instrument — play virtual instruments",
                    TrackType.MIDI to "MIDI — drive external gear",
                ).forEach { (type, desc) ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            viewModel.addTrack(type)
                            showAddTrackSheet = false
                        }.padding(horizontal = 24.dp, vertical = 14.dp),
                    ) {
                        Column {
                            Text(type.name.lowercase().replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.bodyLarge)
                            Text(desc, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArrangerTopBar(
    positionLabel: String,
    tool: EditorTool,
    onToolChange: (EditorTool) -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onOpenMixer: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Back")
            }
            Text(positionLabel, style = NumericReadoutLarge,
                modifier = Modifier.semantics { contentDescription = "Playhead position $positionLabel" })
            Spacer(Modifier.weight(1f))
            // Tool selector.
            EditorTool.entries.forEach { t ->
                FilledIconButton(
                    onClick = { onToolChange(t) },
                    modifier = Modifier.padding(horizontal = 2.dp).size(36.dp),
                    colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (tool == t) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (tool == t) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(t.icon(), contentDescription = t.description(), modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(Icons.Default.Undo, contentDescription = "Undo")
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(Icons.Default.Redo, contentDescription = "Redo")
            }
            TextButton(onClick = onOpenMixer) { Text("Mixer") }
        }
    }
}

private fun EditorTool.icon() = when (this) {
    EditorTool.SELECT -> androidx.compose.material.icons.Icons.Default.TouchApp
    EditorTool.SPLIT -> Icons.Default.ContentCut
    EditorTool.ERASE -> Icons.Default.Delete
    EditorTool.AUTOMATION -> androidx.compose.material.icons.Icons.Default.ShowChart
    EditorTool.PENCIL -> androidx.compose.material.icons.Icons.Default.Edit
}

private fun EditorTool.description() = when (this) {
    EditorTool.SELECT -> "Select and move tool"
    EditorTool.SPLIT -> "Split tool — tap a clip to cut it"
    EditorTool.ERASE -> "Erase tool — tap a clip to delete it"
    EditorTool.AUTOMATION -> "Automation draw tool"
    EditorTool.PENCIL -> "Pencil tool — draw MIDI notes"
}

@Composable
private fun TrackHeader(
    track: Track,
    heightDp: androidx.compose.ui.unit.Dp,
    onMute: () -> Unit,
    onSolo: () -> Unit,
    onArm: () -> Unit,
    onOpenFx: () -> Unit,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant
        else MaterialTheme.colorScheme.surface,
        shape = S1Shapes.small,
        modifier = Modifier.height(heightDp).fillMaxWidth().clickable(onClick = onSelect),
    ) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(
                track.name, style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                MiniToggle("M", active = track.mute, activeColor = S1Colors.SunsetAmber, onClick = onMute)
                MiniToggle("S", active = track.solo, activeColor = S1Colors.ElectricCyan, onClick = onSolo)
                if (track.type == TrackType.AUDIO || track.type == TrackType.INSTRUMENT) {
                    MiniToggle("●", active = track.input.armed, activeColor = S1Colors.RecordRed, onClick = onArm)
                }
                MiniToggle("FX", active = track.inserts.isNotEmpty(),
                    activeColor = MaterialTheme.colorScheme.primary, onClick = onOpenFx)
            }
        }
    }
}

@Composable
private fun MiniToggle(label: String, active: Boolean, activeColor: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(24.dp)
            .background(
                color = if (active) activeColor else MaterialTheme.colorScheme.surfaceVariant,
                shape = S1Shapes.extraSmall,
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = "$label ${if (active) "on" else "off"}" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp)),
            color = if (active) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One track's clip lane: draws visible clips + handles clip gestures. */
@Composable
private fun TrackLane(
    track: Track,
    heightPx: Float,
    pixelsPerFrame: Float,
    firstVisibleFrame: Long,
    lastVisibleFrame: Long,
    tool: EditorTool,
    selection: Selection,
    onSelectClip: (ClipId) -> Unit,
    onMoveClip: (ClipId, Long) -> Unit,
    onSplitAt: (Long) -> Unit,
    onOpenClip: (Clip) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibleClips = track.clips.filter {
        it.startFrame <= lastVisibleFrame && it.endFrame >= firstVisibleFrame
    }
    var dragClip by remember { mutableStateOf<Clip?>(null) }
    var dragOffsetFrames by remember { mutableFloatStateOf(0f) }

    Canvas(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), S1Shapes.small)
            .pointerInput(tool, visibleClips.size, pixelsPerFrame, firstVisibleFrame) {
                detectTapGestures(
                    onTap = { offset ->
                        val frame = firstVisibleFrame + (offset.x / pixelsPerFrame).toLong()
                        val clip = track.clips.firstOrNull { frame in it.startFrame until it.endFrame }
                        when {
                            tool == EditorTool.SPLIT && clip != null -> onSplitAt(frame)
                            clip != null -> onSelectClip(clip.id)
                        }
                    },
                    onDoubleTap = { offset ->
                        val frame = firstVisibleFrame + (offset.x / pixelsPerFrame).toLong()
                        track.clips.firstOrNull { frame in it.startFrame until it.endFrame }
                            ?.let(onOpenClip)
                    },
                )
            }
            .pointerInput(tool, visibleClips.size, pixelsPerFrame, firstVisibleFrame) {
                if (tool != EditorTool.SELECT) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val frame = firstVisibleFrame + (offset.x / pixelsPerFrame).toLong()
                        dragClip = track.clips.firstOrNull { frame in it.startFrame until it.endFrame }
                        dragOffsetFrames = 0f
                    },
                    onDragEnd = {
                        dragClip?.let { clip ->
                            val newStart = clip.startFrame + dragOffsetFrames.toLong()
                            onMoveClip(clip.id, newStart.coerceAtLeast(0))
                        }
                        dragClip = null
                    },
                ) { _, dragAmount ->
                    dragOffsetFrames += dragAmount / pixelsPerFrame
                }
            },
    ) {
        val laneHeight = size.height
        // Row guides (beat lines).
        // Clips.
        for (clip in visibleClips) {
            val dragShift = if (dragClip?.id == clip.id) dragOffsetFrames else 0f
            val x = ((clip.startFrame - firstVisibleFrame) + dragShift) * pixelsPerFrame
            val w = clip.lengthFrames * pixelsPerFrame
            if (x > size.width || x + w < 0) continue
            val selected = (selection as? Selection.Clip)?.clipId == clip.id
            val color = S1Colors.TrackPalette[track.color.ordinal % S1Colors.TrackPalette.size]
            drawRoundRect(
                color = color.copy(alpha = if (clip.muted) 0.25f else 0.85f),
                topLeft = Offset(x, 2f),
                size = Size(w.coerceAtLeast(2f), laneHeight - 4f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f),
            )
            if (selected) {
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(x, 2f),
                    size = Size(w.coerceAtLeast(2f), laneHeight - 4f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f),
                    style = Stroke(width = 2.5f),
                )
            }
            // Content: waveform peaks or MIDI note preview.
            androidx.compose.ui.graphics.drawscope.clipRect(left = x.coerceAtLeast(0f), right = (x + w).coerceAtMost(size.width)) {
                if (clip is com.studioone.mobile.core.model.AudioClip) {
                    drawWaveformForClip(clip, x, w, laneHeight, pixelsPerFrame, firstVisibleFrame, color)
                } else if (clip is MidiClip) {
                    drawMidiNotesPreview(clip, x, w, laneHeight, pixelsPerFrame, firstVisibleFrame)
                }
            }
        }
    }
}

@Composable
private fun PlayheadLine(
    frame: Long,
    pixelsPerFrame: Float,
    scrollFrames: Long,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val x = (frame - scrollFrames) * pixelsPerFrame
        if (x in 0f..size.width) {
            drawLine(
                color = S1Colors.RecordRed,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2.5f,
            )
        }
    }
}

@Composable
private fun LoopOverlay(
    startFrame: Long,
    endFrame: Long,
    pixelsPerFrame: Float,
    scrollFrames: Long,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val x1 = (startFrame - scrollFrames) * pixelsPerFrame
        val x2 = (endFrame - scrollFrames) * pixelsPerFrame
        drawRect(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
            topLeft = Offset(x1, 0f),
            size = Size(x2 - x1, size.height),
        )
    }
}

@Composable
private fun TransportBar(
    state: ArrangerUiState,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onRecord: () -> Unit,
    onToggleMetronome: () -> Unit,
    onToggleLoop: () -> Unit,
    onAddTrack: () -> Unit,
    onOpenLoops: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(onClick = onAddTrack) {
                Icon(Icons.Default.Add, contentDescription = "Add track")
            }
            IconButton(onClick = onOpenLoops) {
                Icon(androidx.compose.material.icons.Icons.Default.LibraryMusic, contentDescription = "Loop library")
            }
            IconButton(onClick = onToggleMetronome) {
                Icon(
                    androidx.compose.material.icons.Icons.Default.AvTimer,
                    contentDescription = "Metronome ${if (state.metronomeOn) "on" else "off"}",
                    tint = if (state.metronomeOn) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onToggleLoop) {
                Icon(
                    Icons.Default.Repeat,
                    contentDescription = "Loop ${if (state.loopEnabled) "on" else "off"}",
                    tint = if (state.loopEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            when (state.transport) {
                TransportState.PLAYING, TransportState.RECORDING ->
                    IconButton(onClick = onStop) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(34.dp))
                    }
                TransportState.PAUSED -> {
                    IconButton(onClick = onPlay) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play", modifier = Modifier.size(34.dp))
                    }
                    IconButton(onClick = onStop) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(34.dp))
                    }
                }
                else -> {
                    IconButton(onClick = onPlay) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play",
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp))
                    }
                }
            }
            S1RecordButton(
                text = if (state.transport == TransportState.RECORDING) "Stop" else "Rec",
                onClick = { if (state.transport == TransportState.RECORDING) onStop() else onRecord() },
                armed = state.transport == TransportState.RECORDING,
                modifier = Modifier.width(84.dp).height(44.dp),
            )
        }
    }
}

@Composable
private fun RecordingHudBar(hud: RecordingHud) {
    Surface(
        color = S1Colors.RecordRed.copy(alpha = 0.92f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).background(Color.White, androidx.compose.foundation.shape.CircleShape))
            Spacer(Modifier.width(10.dp))
            Text("Recording · ${hud.trackName}", color = Color.White, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            Text(
                com.studioone.mobile.core.common.TimeMath.formatClock(hud.elapsedFrames, 48_000),
                color = Color.White, style = NumericReadout,
            )
        }
    }
}
