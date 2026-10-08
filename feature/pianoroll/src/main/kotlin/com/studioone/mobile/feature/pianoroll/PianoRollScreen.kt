package com.studioone.mobile.feature.pianoroll

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studioone.mobile.core.designsystem.theme.S1Colors
import com.studioone.mobile.core.model.MidiNote
import com.studioone.mobile.core.model.SnapDivision

private const val KEY_HEIGHT_DP = 22f
private const val KEYBOARD_WIDTH_DP = 44f

/**
 * Piano roll editor.
 *
 * Grid: vertical = 128 keys (22dp each, black keys shaded, C labels),
 * horizontal = ticks at [PianoRollUiState.pixelsPerTick] zoom. Gestures:
 *  * tap (DRAW): create note snapped to grid (+ scale lock when on)
 *  * tap note (SELECT): select; drag body: move (ticks+keys); drag right
 *    edge: resize; drag in velocity lane: velocity
 *  * tap note (ERASE): delete
 *  * pinch: zoom ticks axis; two-finger vertical scroll: key axis
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PianoRollScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PianoRollViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val clip = state.clip ?: return
    var showSnapMenu by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(clip.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = { viewModel.saveNow(); onBack() }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = viewModel::undo, enabled = state.canUndo) { Icon(Icons.Default.Undo, "Undo") }
                IconButton(onClick = viewModel::redo, enabled = state.canRedo) { Icon(Icons.Default.Redo, "Redo") }
                IconButton(onClick = viewModel::saveNow) { Icon(Icons.Default.Save, "Save") }
            },
        )

        // Toolbar: tools, snap, quantize, scale lock, velocity lane.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PianoRollTool.entries.forEach { tool ->
                FilterChip(
                    selected = state.tool == tool,
                    onClick = { viewModel.setTool(tool) },
                    label = { Text(tool.label(), fontSize = 11.sp) },
                )
            }
            Box {
                FilterChip(
                    selected = false,
                    onClick = { showSnapMenu = true },
                    label = { Text("Snap ${state.snap.label}", fontSize = 11.sp) },
                )
                DropdownMenu(expanded = showSnapMenu, onDismissRequest = { showSnapMenu = false }) {
                    SnapDivision.entries.forEach { d ->
                        DropdownMenuItem(
                            text = { Text(d.label) },
                            onClick = { viewModel.setSnap(d); showSnapMenu = false },
                        )
                    }
                }
            }
            TextButton(onClick = viewModel::quantizeSelectionOrAll) { Text("Quantize", fontSize = 12.sp) }
            FilterChip(
                selected = state.scaleLock != null,
                onClick = viewModel::toggleScaleLock,
                label = { Text(state.scaleLock?.toString() ?: "Scale", fontSize = 11.sp) },
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = viewModel::toggleVelocityLane) {
                Text(if (state.showVelocityLane) "Hide velocity" else "Velocity", fontSize = 12.sp)
            }
        }

        // Draw length + velocity for the DRAW tool.
        if (state.tool == PianoRollTool.DRAW) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Length", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = state.drawLengthTicks.toFloat(),
                    onValueChange = { viewModel.setDrawLength(it.toLong().coerceAtLeast(15)) },
                    valueRange = 30f..1920f,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text("Vel ${state.drawVelocity}", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = state.drawVelocity.toFloat(),
                    onValueChange = { viewModel.setDrawVelocity(it.toInt()) },
                    valueRange = 1f..127f,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
            }
        }

        // ── Grid ─────────────────────────────────────────────────────────────
        val keyHeight = KEY_HEIGHT_DP.dp
        val totalGridHeight = 128 * KEY_HEIGHT_DP
        val velocityLaneHeight = if (state.showVelocityLane) 96.dp else 0.dp

        Row(Modifier.weight(1f).fillMaxWidth()) {
            // Keyboard column (scrolls with the grid vertically).
            val verticalScroll = rememberScrollState()
            KeyboardColumn(
                modifier = Modifier.width(KEYBOARD_WIDTH_DP).verticalScroll(verticalScroll),
                keyHeight = keyHeight,
            )
            Box(Modifier.weight(1f)) {
                val transform = rememberTransformableState { zoom, pan, _ ->
                    viewModel.setZoom(state.pixelsPerTick * zoom)
                    viewModel.scrollBy(-(pan.x / state.pixelsPerTick).toLong())
                }
                NoteGrid(
                    clip = clip,
                    state = state,
                    viewModel = viewModel,
                    keyHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { keyHeight.toPx() },
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(verticalScroll)
                        .transformable(transform),
                )
            }
        }

        // Velocity lane.
        if (state.showVelocityLane) {
            VelocityLane(
                clip = clip,
                state = state,
                onVelocity = { id, v -> viewModel.setVelocity(id, v) },
                modifier = Modifier.fillMaxWidth().height(velocityLaneHeight),
            )
        }
    }
}

@Composable
private fun KeyboardColumn(modifier: Modifier, keyHeight: androidx.compose.ui.unit.Dp) {
    Column(modifier) {
        // Keys drawn top = 127 down to 0.
        for (key in 127 downTo 0) {
            val isBlack = key % 12 in listOf(1, 3, 6, 8, 10)
            val isC = key % 12 == 0
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(keyHeight)
                    .background(
                        when {
                            isC -> S1Colors.ElectricCyan.copy(alpha = 0.22f)
                            isBlack -> MaterialTheme.colorScheme.surfaceVariant
                            else -> MaterialTheme.colorScheme.surface
                        },
                    ),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (isC) {
                    Text("C${key / 12 - 1}", fontSize = 9.sp, modifier = Modifier.padding(start = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun NoteGrid(
    clip: com.studioone.mobile.core.model.MidiClip,
    state: PianoRollUiState,
    viewModel: PianoRollViewModel,
    keyHeightPx: Float,
    modifier: Modifier = Modifier,
) {
    val ppTick = state.pixelsPerTick
    val scrollTicks = state.scrollTicks
    val grid = com.studioone.mobile.core.midi.MidiQuantizer.divisionTicks(state.snap)
    // Hoist theme colors: the Canvas draw lambda is not @Composable.
    val scaleErrorTint = MaterialTheme.colorScheme.error.copy(alpha = 0.06f)

    Canvas(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(state.tool, ppTick, scrollTicks, clip.notes.size, state.scaleLock) {
                detectTapGestures { offset ->
                    val tick = scrollTicks + (offset.x / ppTick).toLong()
                    val key = 127 - (offset.y / keyHeightPx).toInt()
                    val hit = clip.notes.firstOrNull { n ->
                        tick in n.startTick until n.endTick && n.key == key
                    }
                    when {
                        hit != null && state.tool == PianoRollTool.ERASE -> viewModel.deleteNote(hit.id)
                        hit != null && state.tool == PianoRollTool.SELECT -> viewModel.selectNote(hit.id, false)
                        hit == null && state.tool == PianoRollTool.DRAW -> viewModel.addNote(key, tick)
                        hit == null -> viewModel.clearSelection()
                    }
                }
            }
            .pointerInput(state.tool, ppTick, scrollTicks, clip.notes.size) {
                if (state.tool != PianoRollTool.SELECT) return@pointerInput
                var dragNote: MidiNote? = null
                var dragStart = Offset.Zero
                var resizeMode = false
                detectDragGestures(
                    onDragStart = { offset ->
                        val tick = scrollTicks + (offset.x / ppTick).toLong()
                        val key = 127 - (offset.y / keyHeightPx).toInt()
                        dragNote = clip.notes.firstOrNull { n -> tick in n.startTick until n.endTick && n.key == key }
                        dragStart = offset
                        // Right 20% of the note = resize handle.
                        resizeMode = dragNote?.let { n ->
                            tick > n.startTick + n.durationTicks * 0.8
                        } ?: false
                    },
                    onDragEnd = { dragNote = null },
                ) { change: PointerInputChange, dragAmount: Offset ->
                    change.consume()
                    val note = dragNote ?: return@detectDragGestures
                    val deltaTicks = (dragAmount.x / ppTick).toLong()
                    val deltaKeys = -(dragAmount.y / keyHeightPx).toInt()
                    if (resizeMode) {
                        viewModel.resizeNote(note.id, note.durationTicks + deltaTicks)
                    } else {
                        viewModel.moveNote(note.id, deltaTicks, deltaKeys)
                    }
                    dragStart += dragAmount
                }
            },
    ) {
        val width = size.width
        val height = 128 * keyHeightPx

        // Horizontal key rows.
        for (key in 0..127) {
            val y = (127 - key) * keyHeightPx
            val isBlack = key % 12 in listOf(1, 3, 6, 8, 10)
            if (isBlack) {
                drawRect(color = Color.Black.copy(alpha = 0.18f), topLeft = Offset(0f, y), size = Size(width, keyHeightPx))
            }
            if (key % 12 == 0) {
                drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, y + keyHeightPx), Offset(width, y + keyHeightPx), 1.5f)
            }
            // Scale-lock highlighting.
            val scale = state.scaleLock
            if (scale != null && !scale.scale.contains(key % 12, scale.tonicPc)) {
                drawRect(color = scaleErrorTint, topLeft = Offset(0f, y), size = Size(width, keyHeightPx))
            }
        }

        // Vertical grid lines.
        if (grid > 0) {
            val firstTick = (scrollTicks / grid) * grid
            var tick = firstTick
            while (true) {
                val x = (tick - scrollTicks) * ppTick
                if (x > width) break
                val isBeat = tick % com.studioone.mobile.core.model.MidiConstants.PPQ == 0L
                val isBar = tick % (com.studioone.mobile.core.model.MidiConstants.PPQ * 4) == 0L
                drawLine(
                    color = when {
                        isBar -> Color.White.copy(alpha = 0.35f)
                        isBeat -> Color.White.copy(alpha = 0.2f)
                        else -> Color.White.copy(alpha = 0.07f)
                    },
                    start = Offset(x, 0f), end = Offset(x, height),
                    strokeWidth = if (isBar) 2f else 1f,
                )
                tick += grid
            }
        }

        // Notes.
        for (note in clip.notes) {
            val x = (note.startTick - scrollTicks) * ppTick
            val w = note.durationTicks * ppTick
            if (x > width || x + w < 0) continue
            val y = (127 - note.key) * keyHeightPx + 2f
            val selected = note.id in state.selectedNoteIds
            drawRoundRect(
                color = if (note.muted) S1Colors.MidiNoteFill.copy(alpha = 0.3f) else S1Colors.MidiNoteFill,
                topLeft = Offset(x, y),
                size = Size(w.coerceAtLeast(4f), keyHeightPx - 4f),
                cornerRadius = CornerRadius(4f),
            )
            // Velocity as inner fill height.
            drawRoundRect(
                color = Color.White.copy(alpha = 0.25f * note.velocity / 127f),
                topLeft = Offset(x, y + (keyHeightPx - 4f) * (1f - note.velocity / 127f)),
                size = Size(w.coerceAtLeast(4f), (keyHeightPx - 4f) * (note.velocity / 127f)),
                cornerRadius = CornerRadius(4f),
            )
            if (selected) {
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(x, y),
                    size = Size(w.coerceAtLeast(4f), keyHeightPx - 4f),
                    cornerRadius = CornerRadius(4f),
                    style = Stroke(2.5f),
                )
            }
        }
    }
}

@Composable
private fun VelocityLane(
    clip: com.studioone.mobile.core.model.MidiClip,
    state: PianoRollUiState,
    onVelocity: (Long, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ppTick = state.pixelsPerTick
    val scrollTicks = state.scrollTicks
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(clip.notes.size, ppTick, scrollTicks) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val tick = scrollTicks + (change.position.x / ppTick).toLong()
                        val note = clip.notes.firstOrNull { tick in it.startTick until it.endTick } ?: return@detectDragGestures
                        val v = (127 * (1f - change.position.y / size.height)).toInt()
                        onVelocity(note.id, v.coerceIn(1, 127))
                    }
                },
        ) {
            for (note in clip.notes) {
                val x = (note.startTick - scrollTicks) * ppTick
                if (x !in -10f..size.width + 10f) continue
                val h = size.height * (note.velocity / 127f)
                drawLine(
                    color = S1Colors.SunsetAmber,
                    start = Offset(x, size.height),
                    end = Offset(x, size.height - h),
                    strokeWidth = 6f,
                )
                drawCircle(color = S1Colors.SunsetAmber, radius = 5f, center = Offset(x, size.height - h))
            }
        }
    }
}

private fun PianoRollTool.label() = when (this) {
    PianoRollTool.SELECT -> "Select"
    PianoRollTool.DRAW -> "Draw"
    PianoRollTool.ERASE -> "Erase"
}
