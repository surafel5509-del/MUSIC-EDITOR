package com.studioone.feature.instruments

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studioone.core.designsystem.component.StudioSegmentedControl
import com.studioone.core.designsystem.theme.trackColor

enum class InstrumentTab { PADS, KEYS, SEQUENCER }

/** Play surface: pads / keyboard / drum step sequencer. */
@Composable
fun InstrumentsScreen(
    modifier: Modifier = Modifier,
    viewModel: InstrumentsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tabHolder = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(InstrumentTab.PADS) }
    val tab by tabHolder

    Column(modifier.fillMaxSize().padding(12.dp)) {
        StudioSegmentedControl(
            options = InstrumentTab.entries,
            selected = tab,
            onSelected = { tabHolder.value = it },
            labelProvider = { t ->
                when (t) {
                    InstrumentTab.PADS -> stringResource(R.string.instruments_pads)
                    InstrumentTab.KEYS -> stringResource(R.string.instruments_keys)
                    InstrumentTab.SEQUENCER -> stringResource(R.string.instruments_sequencer)
                }
            },
        )

        when (tab) {
            InstrumentTab.PADS -> PadGrid(onPad = viewModel::togglePad, modifier = Modifier.weight(1f))
            InstrumentTab.KEYS -> KeyboardView(
                onNoteOn = viewModel::noteOn,
                onNoteOff = viewModel::noteOff,
                modifier = Modifier.weight(1f),
            )
            InstrumentTab.SEQUENCER -> StepSequencer(
                steps = state.steps,
                activeStep = state.activeStep,
                running = state.runningSequencer,
                onToggleStep = viewModel::toggleStep,
                onToggleRun = viewModel::toggleSequencer,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 4x4 velocity-sensitive drum pad grid. */
@Composable
fun PadGrid(onPad: (Int) -> Unit, modifier: Modifier = Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = modifier.padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(DrumPadNotes) { index, _ ->
            Surface(
                modifier = Modifier.aspectRatio(1f),
                shape = RoundedCornerShape(14.dp),
                color = trackColor(index).copy(alpha = 0.85f),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(index) { detectTapGestures(onTap = { onPad(index) }) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        PAD_LABELS.getOrElse(index) { "${index + 1}" },
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

private val PAD_LABELS = arrayOf(
    "Kick", "Snare", "CH", "OH", "Clap", "Tom L", "Crash", "Ride",
    "Tom M", "Tom H", "Cowbell", "Shaker", "Tamb", "Conga", "Bongo", "Wood",
)

/** One-octave-strip keyboard with tap-down/up semantics. */
@Composable
fun KeyboardView(
    onNoteOn: (Int, Int) -> Unit,
    onNoteOff: (Int) -> Unit,
    modifier: Modifier = Modifier,
    octave: Int = 4,
) {
    val whiteKeys = listOf(0, 2, 4, 5, 7, 9, 11)
    val blackKeys = mapOf(0 to 1, 2 to 3, 5 to 6, 7 to 8, 9 to 10)
    val base = (octave + 1) * 12

    Box(modifier.padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(220.dp)) {
            whiteKeys.forEach { pc ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                        .pointerInput(pc) {
                            detectTapGestures(
                                onPress = {
                                    val pitch = base + pc
                                    onNoteOn(pitch, 100)
                                    tryAwaitRelease()
                                    onNoteOff(pitch)
                                },
                            )
                        },
                ) {
                    Text(
                        NOTE_NAMES[pc],
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Black,
                    )
                }
            }
        }
        // Black keys overlaid on top.
        Row(Modifier.fillMaxWidth().height(130.dp)) {
            whiteKeys.forEachIndexed { index, pc ->
                Box(Modifier.weight(1f)) {
                    val blackPc = blackKeys[pc]
                    if (blackPc != null) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .fillMaxWidth(0.6f)
                                .fillMaxHeight()
                                .background(Color.Black, RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
                                .pointerInput(blackPc) {
                                    detectTapGestures(
                                        onPress = {
                                            val pitch = base + blackPc
                                            onNoteOn(pitch, 100)
                                            tryAwaitRelease()
                                            onNoteOff(pitch)
                                        },
                                    )
                                },
                        )
                    }
                }
            }
        }
    }
}

private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

/** 8x16 drum step sequencer with playhead highlight. */
@Composable
fun StepSequencer(
    steps: List<BooleanArray>,
    activeStep: Int,
    running: Boolean,
    onToggleStep: (row: Int, step: Int) -> Unit,
    onToggleRun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(top = 12.dp)) {
        TextButton(onClick = onToggleRun) {
            Text(stringResource(if (running) R.string.instruments_stop else R.string.instruments_run))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            steps.forEachIndexed { row, rowSteps ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    rowSteps.forEachIndexed { step, on ->
                        val isActive = running && step == activeStep
                        Surface(
                            onClick = { onToggleStep(row, step) },
                            modifier = Modifier.weight(1f).aspectRatio(1f),
                            shape = RoundedCornerShape(4.dp),
                            color = when {
                                on && isActive -> MaterialTheme.colorScheme.primary
                                on -> MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                                isActive -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)
                                step % 4 == 0 -> MaterialTheme.colorScheme.surfaceVariant
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            },
                        ) {}
                    }
                }
            }
        }
    }
}
