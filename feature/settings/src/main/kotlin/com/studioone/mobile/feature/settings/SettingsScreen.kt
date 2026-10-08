package com.studioone.mobile.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.studioone.mobile.core.designsystem.theme.NumericReadout
import com.studioone.mobile.core.designsystem.theme.S1Colors
import com.studioone.mobile.core.model.BitDepth
import com.studioone.mobile.core.model.EngineBufferSize
import com.studioone.mobile.core.model.EngineSampleRate
import com.studioone.mobile.core.model.LatencyReport

/** Audio + account + privacy settings. Changes to audio settings restart the engine streams. */
@Composable
fun SettingsScreen(
    onOpenAccount: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenUpgrade: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionTitle("Audio Engine")

        state.latency?.let { LatencyCard(it) }

        LabeledRow("Sample rate") {
            EngineSampleRate.entries.forEach { rate ->
                FilterChip(
                    selected = state.settings.sampleRate == rate,
                    onClick = { viewModel.setSampleRate(rate) },
                    label = { Text("${rate.hz / 1000}.${if (rate.hz % 1000 == 100) "1" else "0"}k", fontSize = 10.sp) },
                )
            }
        }
        LabeledRow("Buffer size") {
            EngineBufferSize.entries.forEach { size ->
                FilterChip(
                    selected = state.settings.bufferSize == size,
                    onClick = { viewModel.setBufferSize(size) },
                    label = { Text("${size.frames}", fontSize = 10.sp) },
                )
            }
        }
        LabeledRow("Record bit depth") {
            listOf(BitDepth.PCM_16, BitDepth.PCM_24, BitDepth.FLOAT_32).forEach { depth ->
                FilterChip(
                    selected = state.settings.recordingBitDepth == depth,
                    onClick = { viewModel.setBitDepth(depth) },
                    label = { Text(depth.name.replace("_", "-"), fontSize = 10.sp) },
                )
            }
        }
        ToggleRow("Low-latency mode (AAudio/MMAP)", state.settings.lowLatencyMode, viewModel::setLowLatency)
        ToggleRow("Exclusive mode (pro audio devices)", state.settings.exclusiveMode, viewModel::setExclusive)

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Record latency compensation", style = MaterialTheme.typography.bodyMedium)
                Text("Nudge recordings earlier/later to fix device offset",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("%.1f ms".format(state.settings.recordLatencyCompensationMs), style = NumericReadout)
        }
        Slider(
            value = state.settings.recordLatencyCompensationMs,
            onValueChange = viewModel::setLatencyCompensation,
            valueRange = -20f..20f,
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SectionTitle("Metronome & Count-in")
        ToggleRow("Metronome while recording", state.settings.metronomeDuringRecord, viewModel::setMetronomeRecord)
        LabeledRow("Count-in") {
            listOf(0, 1, 2, 4).forEach { bars ->
                FilterChip(
                    selected = state.settings.countInBars == bars,
                    onClick = { viewModel.setCountIn(bars) },
                    label = { Text(if (bars == 0) "Off" else "$bars bar${if (bars > 1) "s" else ""}", fontSize = 10.sp) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Metronome volume", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Slider(
                value = state.settings.metronomeVolume,
                onValueChange = viewModel::setMetronomeVolume,
                modifier = Modifier.weight(1f),
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SectionTitle("Account")
        ClickRow("Account & subscription") { onOpenAccount() }
        ClickRow("Privacy & data (GDPR)") { onOpenPrivacy() }
        ClickRow("Upgrade to Pro") { onOpenUpgrade() }

        Spacer(Modifier.height(24.dp))
        Text(
            "StudioOne Mobile · engine build ${state.engineBuild}",
            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun LabeledRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ClickRow(label: String, onClick: () -> Unit) {
    Text(label, style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp))
}

@Composable
private fun LatencyCard(report: LatencyReport) {
    Column(
        Modifier.fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LatencyStat("Round trip", "%.1f ms".format(report.roundTripMs),
                good = report.roundTripMs < 10f)
            LatencyStat("In", "%.1f ms".format(report.inputLatencyMs), good = report.inputLatencyMs < 6f)
            LatencyStat("Out", "%.1f ms".format(report.outputLatencyMs), good = report.outputLatencyMs < 6f)
            LatencyStat("Backend", report.backendName, good = report.usingMmap)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Buffer ${report.bufferFrames}f @ ${report.sampleRate}Hz", fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("xruns ${report.xrunCount}", fontSize = 10.sp,
                color = if (report.xrunCount > 0) S1Colors.RecordRed else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LatencyStat(label: String, value: String, good: Boolean) {
    Column {
        Text(value, style = NumericReadout, color = if (good) S1Colors.MeterGreen else S1Colors.SunsetAmber)
        Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
