package com.studioone.mobile.feature.effects

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studioone.mobile.core.designsystem.components.Knob
import com.studioone.mobile.core.designsystem.components.S1PrimaryButton
import com.studioone.mobile.core.designsystem.components.Taper
import com.studioone.mobile.core.designsystem.theme.S1Colors
import com.studioone.mobile.core.designsystem.theme.S1Shapes
import com.studioone.mobile.core.model.FxPluginId
import com.studioone.mobile.core.model.FxSlot
import com.studioone.mobile.core.model.ParamTaper
import com.studioone.mobile.core.model.ParamUnit
import com.studioone.mobile.core.model.PluginCategory

/**
 * FX rack screen: slot list (drag-reorder), plugin browser sheet grouped by
 * category, and a generated parameter editor (Knob per spec, unit-aware
 * labels, taper mapping). This screen doubles as the bus/master rack via the
 * target argument in the full app wiring.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FxRackScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FxRackViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text("${state.trackName} · FX Rack") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { viewModel.openBrowser(null) }) {
                    Icon(Icons.Default.Add, "Add effect")
                }
            },
        )

        // Slot list.
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.slots.size) { index ->
                val slot = state.slots[index]
                SlotRow(
                    index = index,
                    slot = slot,
                    selected = state.selectedSlot == index,
                    onSelect = { viewModel.selectSlot(index) },
                    onBypass = { viewModel.toggleBypass(index) },
                    onRemove = { viewModel.removeSlot(index) },
                )
            }
            if (state.slots.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Text("No effects yet — tap + to browse the rack",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // Parameter editor for the selected slot.
        val selected = state.slots.getOrNull(state.selectedSlot)
        if (selected != null) {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(selected.plugin.displayName, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Save, "Save preset") }
                    }
                    // Wet mix.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Wet/Dry", style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.size(width = 56.dp, height = 20.dp))
                        Slider(
                            value = selected.wetMix,
                            onValueChange = { viewModel.setWetMix(state.selectedSlot, it) },
                            modifier = Modifier.weight(1f),
                        )
                        Text("${(selected.wetMix * 100).toInt()}%", fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    // Generated parameter grid from the catalog.
                    val specs = FxParamCatalog.specs(selected.plugin)
                    specs.chunked(4).forEach { rowSpecs ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly) {
                            rowSpecs.forEach { spec ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.weight(1f)) {
                                    val raw = selected.params[spec.index] ?: spec.defaultValue
                                    val norm = spec.normalize(raw)
                                    Knob(
                                        value = norm,
                                        onValueChange = { n ->
                                            viewModel.setParam(spec.index, state.selectedSlot, spec.denormalize(n))
                                        },
                                        label = spec.name,
                                        modifier = Modifier.size(44.dp),
                                        accent = if (spec.unit == ParamUnit.DB) S1Colors.SunsetAmber else S1Colors.ElectricCyan,
                                    )
                                    Text(spec.name, fontSize = 8.sp, maxLines = 1,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(spec.format(raw), fontSize = 9.sp, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            // Fill remaining columns to keep knob sizes stable.
                            repeat(4 - rowSpecs.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }

    if (state.showBrowser) {
        PluginBrowserSheet(
            category = state.browseCategory,
            onPickCategory = viewModel::openBrowser,
            onPickPlugin = viewModel::addPlugin,
            onDismiss = viewModel::closeBrowser,
        )
    }

    if (renaming) {
        var presetName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Save preset") },
            text = {
                OutlinedTextField(value = presetName, onValueChange = { presetName = it },
                    label = { Text("Preset name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = { viewModel.savePreset(presetName); renaming = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SlotRow(index: Int, slot: FxSlot, selected: Boolean, onSelect: () -> Unit, onBypass: () -> Unit, onRemove: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = S1Shapes.small,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.DragIndicator, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(10.dp))
            Box(Modifier.size(10.dp).background(
                if (slot.enabled) S1Colors.MeterGreen else MaterialTheme.colorScheme.outline,
                S1Shapes.pill))
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${index + 1}. ${slot.plugin.displayName}", style = MaterialTheme.typography.bodyLarge)
                Text(slot.plugin.category.displayName(), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onBypass) {
                Icon(Icons.Default.PowerSettingsNew,
                    contentDescription = if (slot.enabled) "Bypass" else "Enable",
                    tint = if (slot.enabled) MaterialTheme.colorScheme.onSurfaceVariant else S1Colors.RecordRed)
            }
            IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "Remove") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginBrowserSheet(
    category: PluginCategory?,
    onPickCategory: (PluginCategory?) -> Unit,
    onPickPlugin: (FxPluginId) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Text("Add Effect", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()) {
                FilterChip(selected = category == null, onClick = { onPickCategory(null) },
                    label = { Text("All", fontSize = 11.sp) })
                PluginCategory.entries.take(5).forEach { c ->
                    FilterChip(selected = category == c, onClick = { onPickCategory(c) },
                        label = { Text(c.displayName(), fontSize = 11.sp) })
                }
            }
            Spacer(Modifier.height(12.dp))
            val plugins = FxPluginId.entries.filter {
                it.category != PluginCategory.INSTRUMENT && it.category != PluginCategory.UTILITY &&
                    (category == null || it.category == category)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.height(360.dp)) {
                items(plugins) { plugin ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = S1Shapes.small,
                        modifier = Modifier.fillMaxWidth().clickable { onPickPlugin(plugin) },
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(plugin.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text(plugin.category.displayName(), fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (plugin.tailMs > 0) {
                                Text("tail ${plugin.tailMs}ms", fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun PluginCategory.displayName(): String = when (this) {
    PluginCategory.DYNAMICS -> "Dynamics"
    PluginCategory.EQ -> "EQ"
    PluginCategory.FILTER -> "Filters"
    PluginCategory.REVERB -> "Reverb"
    PluginCategory.DELAY -> "Delay"
    PluginCategory.MODULATION -> "Modulation"
    PluginCategory.SATURATION -> "Saturation"
    PluginCategory.PITCH -> "Pitch/Time"
    PluginCategory.CREATIVE -> "Creative"
    PluginCategory.UTILITY -> "Utility"
    PluginCategory.INSTRUMENT -> "Instrument"
}

// ── Param spec <-> normalized knob space ─────────────────────────────────────

private fun com.studioone.mobile.core.model.FxParamSpec.normalize(raw: Float): Float =
    when (taper) {
        ParamTaper.LINEAR, ParamTaper.DECIBEL -> ((raw - min) / (max - min)).coerceIn(0f, 1f)
        ParamTaper.LOG, ParamTaper.FREQUENCY -> {
            val lo = kotlin.math.ln(min.coerceAtLeast(0.001f))
            val hi = kotlin.math.ln(max.coerceAtLeast(0.002f))
            ((kotlin.math.ln(raw.coerceAtLeast(0.001f)) - lo) / (hi - lo)).coerceIn(0f, 1f)
        }
        ParamTaper.EXP -> {
            val t = ((raw - min) / (max - min)).coerceIn(0f, 1f)
            kotlin.math.sqrt(t)
        }
    }

private fun com.studioone.mobile.core.model.FxParamSpec.denormalize(norm: Float): Float =
    when (taper) {
        ParamTaper.LINEAR, ParamTaper.DECIBEL -> min + (max - min) * norm.coerceIn(0f, 1f)
        ParamTaper.LOG, ParamTaper.FREQUENCY -> {
            val lo = kotlin.math.ln(min.coerceAtLeast(0.001f))
            val hi = kotlin.math.ln(max.coerceAtLeast(0.002f))
            kotlin.math.exp(lo + (hi - lo) * norm.coerceIn(0f, 1f))
        }
        ParamTaper.EXP -> min + (max - min) * (norm.coerceIn(0f, 1f) * norm.coerceIn(0f, 1f))
    }

private fun com.studioone.mobile.core.model.FxParamSpec.format(raw: Float): String = when (unit) {
    ParamUnit.DB -> "%.1f dB".format(raw)
    ParamUnit.HZ -> if (raw >= 1000) "%.1fk".format(raw / 1000) else "%.0f Hz".format(raw)
    ParamUnit.MS -> "%.0f ms".format(raw)
    ParamUnit.PERCENT -> "%.0f%%".format(raw * 100)
    ParamUnit.SEMITONES -> "%+.1f st".format(raw)
    ParamUnit.RATIO -> "%.1f:1".format(raw)
    ParamUnit.DEGREES -> "%.0f°".format(raw)
    ParamUnit.BPM -> "%.0f".format(raw)
    ParamUnit.RAW -> "%.2f".format(raw)
}
