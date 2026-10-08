package com.studioone.mobile.feature.instruments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studioone.mobile.core.designsystem.theme.S1Colors
import com.studioone.mobile.core.designsystem.theme.S1Shapes

/**
 * Drum machine: 4x4 pad grid (velocity-sensitive via drag distance — a full
 * pressure model needs the MIDI MPE path) + 16-step sequencer per pad row.
 */
@Composable
fun DrumMachineScreen(
    projectId: String,
    trackId: String,
    modifier: Modifier = Modifier,
    viewModel: InstrumentsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pattern = state.stepPattern ?: defaultStepPattern()

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp)) {
        // Pad grid.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            state.pads.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { pad ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1.6f)
                                .background(S1Colors.TrackPalette[pad.index % 9].copy(alpha = 0.35f), S1Shapes.pad)
                                .clickable { viewModel.triggerPad(pad.index) }
                                .padding(8.dp),
                            contentAlignment = Alignment.BottomStart,
                        ) {
                            Text(pad.label, fontSize = 11.sp, color = Color.White)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Step Sequencer", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = viewModel::clearPattern) { Text("Clear") }
        }
        // Sequencer rows (first 8 pads).
        Column(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            pattern.rows.take(8).forEachIndexed { rowIndex, row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.pads.getOrNull(rowIndex)?.label ?: "Pad ${rowIndex + 1}",
                        fontSize = 10.sp,
                        modifier = Modifier.size(width = 52.dp, height = 28.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    row.velocities.forEachIndexed { step, velocity ->
                        val active = velocity > 0
                        val isBeat = step % 4 == 0
                        Box(
                            Modifier
                                .size(28.dp)
                                .padding(2.dp)
                                .background(
                                    color = when {
                                        active -> S1Colors.TrackPalette[rowIndex % 9]
                                        isBeat -> MaterialTheme.colorScheme.surfaceVariant
                                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    },
                                    shape = S1Shapes.extraSmall,
                                )
                                .clickable { viewModel.toggleStep(rowIndex, step) },
                        )
                    }
                }
            }
        }
    }
}
