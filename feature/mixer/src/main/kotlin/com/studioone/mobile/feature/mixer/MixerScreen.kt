package com.studioone.mobile.feature.mixer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studioone.mobile.core.designsystem.components.Fader
import com.studioone.mobile.core.designsystem.components.Knob
import com.studioone.mobile.core.designsystem.components.LevelMeter
import com.studioone.mobile.core.designsystem.components.Taper
import com.studioone.mobile.core.designsystem.theme.NumericReadout
import com.studioone.mobile.core.designsystem.theme.S1Colors
import com.studioone.mobile.core.designsystem.theme.S1Shapes
import com.studioone.mobile.core.model.ProjectId
import com.studioone.mobile.core.model.Track
import com.studioone.mobile.core.model.TrackId
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Mixer: horizontally scrollable channel strips + master bus panel with
 * loudness (LUFS) and spectrum analyzer. Phone shows 2-3 strips, tablets
 * 6+; landscape rotates strips to the classic console layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixerScreen(
    projectId: String,
    onBack: () -> Unit,
    onOpenFxRack: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MixerViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId) { viewModel.bind(ProjectId(projectId)) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val project = state.project ?: return
    var showMasterSheet by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text("Mixer", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.Tune, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = { showMasterSheet = true }) {
                    Icon(Icons.Default.GraphicEq, contentDescription = "Master bus")
                }
            },
        )

        LazyRow(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
        ) {
            items(project.tracks, key = { it.id.value }) { track ->
                ChannelStrip(
                    track = track,
                    meter = state.stripMeters[track.id.value],
                    selected = state.selectedTrackId == track.id,
                    onSelect = { viewModel.selectTrack(track.id) },
                    onVolume = { viewModel.setVolume(track.id, it) },
                    onPan = { viewModel.setPan(track.id, it) },
                    onMute = { viewModel.toggleMute(track.id) },
                    onSolo = { viewModel.toggleSolo(track.id) },
                    onArm = { viewModel.toggleArm(track.id) },
                    onSend = { idx, db -> viewModel.setSend(track.id, idx, db) },
                    onOpenFx = { onOpenFxRack(track.id.value) },
                )
            }
            item {
                MasterStrip(
                    master = state.masterMeter,
                    onClick = { showMasterSheet = true },
                )
            }
        }
    }

    if (showMasterSheet) {
        ModalBottomSheet(onDismissRequest = { showMasterSheet = false }) {
            MasterPanel(state = state)
        }
    }
}

@Composable
private fun ChannelStrip(
    track: Track,
    meter: StripMeter?,
    selected: Boolean,
    onSelect: () -> Unit,
    onVolume: (Float) -> Unit,
    onPan: (Float) -> Unit,
    onMute: () -> Unit,
    onSolo: () -> Unit,
    onArm: () -> Unit,
    onSend: (Int, Float) -> Unit,
    onOpenFx: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        shape = S1Shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier.width(88.dp).fillMaxHeight().clickable(onClick = onSelect),
    ) {
        Column(
            Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                track.name, style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            // Pan knob + send knobs row.
            Knob(
                value = (track.pan + 1f) / 2f,
                onValueChange = { onPan(it * 2f - 1f) },
                label = "Pan",
                modifier = Modifier.size(36.dp),
            )
            Text("%.0f%%".format(track.pan * 100), style = NumericReadout, fontSize = 9.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0 until 2) {
                    val send = track.sends.getOrNull(i)
                    Knob(
                        value = if (send == null) 0f else ((send.levelDb + 60f) / 72f).coerceIn(0f, 1f),
                        onValueChange = { onSend(i, it * 72f - 60f) },
                        label = "Send ${i + 1}",
                        modifier = Modifier.size(28.dp),
                        accent = S1Colors.SunsetAmber,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            // Fader + meters.
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                LevelMeter(
                    peakL = meter?.peakL ?: 0f,
                    peakR = meter?.peakR ?: 0f,
                    clipL = meter?.clipL ?: false,
                    clipR = meter?.clipR ?: false,
                    modifier = Modifier.width(12.dp).fillMaxHeight(),
                )
                Fader(
                    db = track.volumeDb,
                    onDbChange = onVolume,
                    label = "${track.name} volume fader",
                    modifier = Modifier.width(44.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            // M/S/R buttons.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StripButton("M", track.mute, S1Colors.SunsetAmber, onMute)
                StripButton("S", track.solo, S1Colors.ElectricCyan, onSolo)
                StripButton("R", track.input.armed, S1Colors.RecordRed, onArm)
            }
            Spacer(Modifier.height(4.dp))
            TextButtonSmall("FX ${track.inserts.size}", onOpenFx)
            Text(
                if (track.volumeDb <= -59f) "-inf" else "%.1f dB".format(track.volumeDb),
                style = NumericReadout, fontSize = 9.sp,
            )
        }
    }
}

@Composable
private fun StripButton(label: String, active: Boolean, activeColor: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(24.dp)
            .background(if (active) activeColor else MaterialTheme.colorScheme.surfaceVariant, S1Shapes.extraSmall)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 10.sp, color = if (active) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TextButtonSmall(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), S1Shapes.pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
private fun MasterStrip(master: MasterMeterFrame, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        shape = S1Shapes.medium,
        modifier = Modifier.width(96.dp).fillMaxHeight().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("MASTER", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                LevelMeter(
                    peakL = master.peakL, peakR = master.peakR,
                    clipL = master.clipL, clipR = master.clipR,
                    modifier = Modifier.width(20.dp).fillMaxHeight(),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text("%.1f".format(master.loudness.momentaryLUFS), style = NumericReadout, color = S1Colors.ElectricCyan)
            Text("LUFS-M", style = NumericReadout, fontSize = 8.sp)
            Text("%.1f".format(master.loudness.truePeakDbtp), style = NumericReadout)
            Text("dBTP", style = NumericReadout, fontSize = 8.sp)
        }
    }
}

@Composable
private fun MasterPanel(state: MixerUiState) {
    val m = state.masterMeter
    Column(Modifier.padding(24.dp).fillMaxWidth()) {
        Text("Master Bus", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            MasterStat("Momentary", "%.1f LUFS".format(m.loudness.momentaryLUFS))
            MasterStat("Short", "%.1f LUFS".format(m.loudness.shortTermLUFS))
            MasterStat("Integrated", "%.1f LUFS".format(m.loudness.integratedLUFS))
            MasterStat("LRA", "%.1f LU".format(m.loudness.loudnessRangeLU))
            MasterStat("True Peak", "%.1f dBTP".format(m.loudness.truePeakDbtp))
        }
        Spacer(Modifier.height(20.dp))
        Text("Spectrum", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        SpectrumView(state.spectrum, Modifier.fillMaxWidth().height(140.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            "Streaming targets: -14 LUFS (Spotify/YouTube) · -16 LUFS (podcasts) · -9 LUFS (club masters)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MasterStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = NumericReadout, color = S1Colors.ElectricCyan)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Log-frequency spectrum bars from the native analyzer (64 bands). */
@Composable
private fun SpectrumView(spectrum: FloatArray?, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant, S1Shapes.small)) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val bands = spectrum ?: return@Canvas
            val barW = size.width / bands.size
            for (i in bands.indices) {
                val db = bands[i].coerceIn(-72f, 0f)
                val norm = (db + 72f) / 72f
                val h = norm * size.height
                drawRect(
                    color = color.copy(alpha = 0.35f + 0.65f * norm),
                    topLeft = androidx.compose.ui.geometry.Offset(i * barW, size.height - h),
                    size = androidx.compose.ui.geometry.Size(barW * 0.82f, h),
                )
            }
        }
    }
}
