package com.studioone.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studioone.core.designsystem.component.StudioSecondaryButton
import com.studioone.core.domain.repository.ColorVisionMode
import com.studioone.core.domain.repository.ThemeMode

/** App settings: audio engine, appearance, accessibility, account, data. */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val audio by viewModel.audioSettings.collectAsStateWithLifecycle()
    val ui by viewModel.uiSettings.collectAsStateWithLifecycle()

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

        // ---- Audio engine ----
        SectionHeader(stringResource(R.string.settings_audio_engine))

        Text(stringResource(R.string.settings_sample_rate), style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(44_100, 48_000, 96_000).forEachIndexed { index, rate ->
                SegmentedButton(
                    selected = audio.sampleRate == rate,
                    onClick = { viewModel.setSampleRate(rate) },
                    shape = SegmentedButtonDefaults.itemShape(index, 3),
                ) { Text("${rate / 1000}k") }
            }
        }

        Text(stringResource(R.string.settings_buffer_size), style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(64, 128, 256, 512).forEachIndexed { index, size ->
                SegmentedButton(
                    selected = audio.bufferSize == size,
                    onClick = { viewModel.setBufferSize(size) },
                    shape = SegmentedButtonDefaults.itemShape(index, 4),
                ) { Text("$size") }
            }
        }

        SwitchRow(stringResource(R.string.settings_low_latency), audio.useLowLatencyPath, viewModel::setLowLatency)
        SwitchRow(stringResource(R.string.settings_opensles_fallback), audio.useOpenSlesFallback, viewModel::setOpenSlesFallback)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${stringResource(R.string.settings_latency_report)}: ${audio.measuredOutputLatencyFrames} frames",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            StudioSecondaryButton(text = "Probe", onClick = { viewModel.probeLatency() })
        }

        // ---- Appearance ----
        SectionHeader(stringResource(R.string.settings_appearance))
        Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = ui.themeMode == mode,
                    onClick = { viewModel.setTheme(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                ) { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }
            }
        }
        SwitchRow(stringResource(R.string.settings_high_contrast), ui.highContrast, viewModel::setHighContrast)

        // ---- Accessibility ----
        SectionHeader(stringResource(R.string.settings_accessibility))
        SwitchRow(stringResource(R.string.settings_large_targets), ui.largeTouchTargets, viewModel::setLargeTargets)
        Text(stringResource(R.string.settings_color_vision), style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ColorVisionMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = ui.colorVisionMode == mode,
                    onClick = { viewModel.setColorVision(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, ColorVisionMode.entries.size),
                ) {
                    Text(
                        when (mode) {
                            ColorVisionMode.NONE -> "Off"
                            ColorVisionMode.PROTANOPIA -> "P"
                            ColorVisionMode.DEUTERANOPIA -> "D"
                            ColorVisionMode.TRITANOPIA -> "T"
                        },
                    )
                }
            }
        }

        // ---- Account ----
        SectionHeader(stringResource(R.string.settings_account))
        StudioSecondaryButton(text = stringResource(R.string.settings_sign_out), onClick = { viewModel.signOut() })
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
