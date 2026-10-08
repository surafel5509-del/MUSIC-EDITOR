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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studioone.mobile.core.designsystem.components.Knob
import com.studioone.mobile.core.designsystem.theme.S1Colors
import com.studioone.mobile.core.designsystem.theme.S1Shapes
import com.studioone.mobile.core.model.InstrumentFamily
import com.studioone.mobile.core.model.InstrumentId
import com.studioone.mobile.core.model.PresetId

/**
 * Instrument browser + editor: family chips, instrument grid (Pro badge on
 * gated items), macro strip for the loaded instrument.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstrumentsScreen(
    projectId: String,
    trackId: String,
    onBack: () -> Unit,
    onUpgradeClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InstrumentsViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId, trackId) {
        viewModel.bind(
            com.studioone.mobile.core.model.ProjectId(projectId),
            com.studioone.mobile.core.model.TrackId(trackId),
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(state.current?.instrument?.displayName ?: "Choose instrument") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
        )
        // Family filter.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(selected = state.selectedFamily == null, onClick = { viewModel.selectFamily(null) },
                label = { Text("All", fontSize = 11.sp) })
            InstrumentFamily.entries.take(4).forEach { family ->
                FilterChip(
                    selected = state.selectedFamily == family,
                    onClick = { viewModel.selectFamily(family) },
                    label = { Text(family.displayName, fontSize = 11.sp) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            val instruments = InstrumentId.entries.filter {
                state.selectedFamily == null || it.family == state.selectedFamily
            }
            items(instruments) { instrument ->
                InstrumentCard(
                    instrument = instrument,
                    selected = state.current?.instrument == instrument,
                    onClick = {
                        if (instrument.requiresPack) onUpgradeClick() // pack download flow
                        else viewModel.loadInstrument(instrument, PresetId("factory-init"))
                    },
                )
            }
        }

        // Macro strip for the loaded instrument.
        state.current?.let { instance ->
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    instance.macros.forEachIndexed { i, value ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Knob(
                                value = value,
                                onValueChange = { viewModel.setMacro(i, it) },
                                label = "Macro ${i + 1}",
                                modifier = Modifier.size(44.dp),
                            )
                            Text("M${i + 1}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstrumentCard(instrument: InstrumentId, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = S1Shapes.medium,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Piano, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(instrument.family.displayName, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                if (instrument.requiresPack) {
                    Icon(Icons.Default.Lock, contentDescription = "Requires sound pack",
                        modifier = Modifier.size(14.dp), tint = S1Colors.SunsetAmber)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(instrument.displayName, style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
        }
    }
}
