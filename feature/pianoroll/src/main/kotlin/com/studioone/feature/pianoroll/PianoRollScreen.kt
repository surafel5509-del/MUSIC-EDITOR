package com.studioone.feature.pianoroll

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studioone.core.designsystem.component.StudioEmptyState
import com.studioone.core.designsystem.component.StudioSegmentedControl
import com.studioone.core.designsystem.component.StudioTextButton
import com.studioone.core.domain.model.MidiClip
import com.studioone.core.domain.model.SnapDivision

/**
 * Piano roll editor: key gutter + note grid. Draw tool stamps notes at the
 * snapped position; select tool picks notes; erase tool deletes. Velocity
 * slider sets the draw velocity (per-note editing via the velocity lane is a
 * milestone-2 enhancement).
 */
@Composable
fun PianoRollScreen(
    modifier: Modifier = Modifier,
    viewModel: PianoRollViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize().padding(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StudioSegmentedControl(
                options = PianoRollTool.entries,
                selected = state.tool,
                onSelected = viewModel::setTool,
                labelProvider = { tool ->
                    when (tool) {
                        PianoRollTool.SELECT -> stringResource(R.string.pianoroll_select_tool)
                        PianoRollTool.DRAW -> stringResource(R.string.pianoroll_draw_tool)
                        PianoRollTool.ERASE -> stringResource(R.string.pianoroll_erase_tool)
                    }
                },
                modifier = Modifier.weight(1f),
            )
            StudioTextButton(
                text = stringResource(R.string.pianoroll_quantize),
                onClick = viewModel::quantizeSelection,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.pianoroll_velocity), style = MaterialTheme.typography.labelMedium)
            Slider(
                value = state.velocityEdit.toFloat(),
                onValueChange = { viewModel.setVelocity(it.toInt()) },
                valueRange = 1f..127f,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            Text("${state.velocityEdit}", style = MaterialTheme.typography.labelMedium)
        }

        val clip = state.clip
        if (clip == null) {
            StudioEmptyState(
                title = stringResource(R.string.pianoroll_title),
                subtitle = stringResource(R.string.pianoroll_no_clip),
            )
        } else {
            PianoRollGrid(
                clip = clip,
                tool = state.tool,
                onTapCell = { frame, pitch ->
                    when (state.tool) {
                        PianoRollTool.DRAW -> {
                            val lengthFrames = 48_000L / 8 // ~1/8 note at 120bpm/44.1k; snap trims it
                            viewModel.addNote(frame, pitch, lengthFrames)
                        }
                        PianoRollTool.ERASE -> {
                            clip.notes.firstOrNull { note ->
                                frame >= note.startFrame && frame < note.startFrame + note.lengthFrames && note.pitch == pitch
                            }?.let { viewModel.eraseNote(it.id) }
                        }
                        PianoRollTool.SELECT -> { /* selection handled in milestone-2 gestures */ }
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Scrollable grid with key gutter. Renders notes as pitch rows. */
@Composable
private fun PianoRollGrid(
    clip: MidiClip,
    tool: PianoRollTool,
    onTapCell: (frame: Long, pitch: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pitchMin = 24
    val pitchMax = 96
    val rowHeight = 18.dp
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val blackKeyColor = MaterialTheme.colorScheme.surfaceVariant
    val noteColor = MaterialTheme.colorScheme.primary

    Box(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            // Key gutter: tap = audition (noteOn) in a later wiring pass.
            Column(Modifier.width(48.dp)) {
                for (pitch in pitchMax downTo pitchMin) {
                    val isBlack = (pitch % 12) in setOf(1, 3, 6, 8, 10)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(rowHeight)
                            .background(if (isBlack) blackKeyColor else MaterialTheme.colorScheme.surface),
                    ) {
                        if (pitch % 12 == 0) {
                            Text(
                                "C${pitch / 12 - 1}",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }
            }

            // Note field.
            val scroll = rememberScrollState()
            Box(
                Modifier
                    .weight(1f)
                    .horizontalScroll(scroll)
                    .pointerInput(tool, clip.id) {
                        detectTapGestures { offset ->
                            val pxPerFrame = 0.004 // 4px per ms at 44.1k baseline
                            val frame = (offset.x / pxPerFrame).toLong()
                            val row = (offset.y / rowHeight.toPx()).toInt()
                            val pitch = pitchMax - row
                            if (pitch in pitchMin..pitchMax) onTapCell(frame, pitch)
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val rowH = size.height / (pitchMax - pitchMin + 1)
                    // Rows + black-key shading.
                    for (pitch in pitchMax downTo pitchMin) {
                        val y = (pitchMax - pitch) * rowH
                        if ((pitch % 12) in setOf(1, 3, 6, 8, 10)) {
                            drawRect(color = blackKeyColor.copy(alpha = 0.5f), topLeft = Offset(0f, y), size = Size(size.width, rowH))
                        }
                        drawLine(color = gridColor, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 1f)
                    }
                    // Notes.
                    val pxPerFrame = 0.004f * 1000f // matches tap mapping scaled to draw width
                    clip.notes.forEach { note ->
                        val x = note.startFrame * size.width / clip.lengthFrames.coerceAtLeast(1)
                        val w = note.lengthFrames * size.width / clip.lengthFrames.coerceAtLeast(1)
                        val y = (pitchMax - note.pitch) * rowH
                        drawRect(
                            color = noteColor.copy(alpha = 0.4f + 0.6f * note.velocity / 127f),
                            topLeft = Offset(x.toFloat(), y + 1f),
                            size = Size(w.toFloat().coerceAtLeast(4f), rowH - 2f),
                        )
                    }
                }
            }
        }
    }
}
