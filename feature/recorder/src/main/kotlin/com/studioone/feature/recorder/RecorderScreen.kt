package com.studioone.feature.recorder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import com.studioone.core.common.util.formatTimecode
import com.studioone.core.designsystem.component.audio.LevelMeter
import com.studioone.core.designsystem.theme.MonoTextStyle
import com.studioone.core.designsystem.theme.RecordRed
import com.studioone.core.ui.rememberPermissionFlow

/**
 * Dedicated capture screen: big record button, live meter, input selector,
 * gain, monitoring and latency readout.
 */
@Composable
fun RecorderScreen(
    onTakeFinished: (wavPath: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecordViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val micPermission = rememberPermissionFlow(android.Manifest.permission.RECORD_AUDIO)
    val micState = micPermission.state

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.recorder_title), style = MaterialTheme.typography.headlineSmall)

        if (!state.isRecording) {
            if (!micState.granted) {
                Text(stringResource(R.string.recorder_need_permission))
                com.studioone.core.designsystem.component.StudioPrimaryButton(
                    text = stringResource(R.string.recorder_grant_permission),
                    onClick = { micPermission.launch() },
                )
                return@Column
            }
        }

        // Meter + timecode.
        Row(
            modifier = Modifier.height(140.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LevelMeter(
                levelLeft = state.meter.getOrElse(0) { 0f },
                levelRight = state.meter.getOrElse(1) { 0f },
                peakLeft = state.meter.getOrElse(0) { 0f },
                peakRight = state.meter.getOrElse(1) { 0f },
                modifier = Modifier.height(120.dp),
            )
            Column {
                Text(
                    formatTimecode(state.elapsedFrames, state.sampleRate),
                    style = MonoTextStyle,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "${stringResource(R.string.recorder_latency)}: %.1f ms".format(state.latencyMs),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "${state.sampleRate / 1000}k · ${state.bufferSize} buf",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Gain.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.recorder_gain), modifier = Modifier.weight(1f))
            Slider(
                value = state.gain,
                onValueChange = viewModel::setGain,
                valueRange = 0f..4f,
                modifier = Modifier.weight(3f),
            )
        }

        // Monitoring + count-in.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.recorder_monitoring), modifier = Modifier.weight(1f))
            Switch(checked = state.monitoringEnabled, onCheckedChange = viewModel::setMonitoring)
        }

        Spacer(Modifier.weight(1f))

        // Record button.
        FloatingActionButton(
            onClick = {
                viewModel.toggleRecording()
                if (!state.isRecording) {
                    // Just stopped: hand the take back to the editor.
                    viewModel.consumeRecordedFile()?.let(onTakeFinished)
                }
            },
            modifier = Modifier.size(96.dp),
            shape = CircleShape,
            containerColor = if (state.isRecording) RecordRed else MaterialTheme.colorScheme.primary,
        ) {
            Icon(
                if (state.isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                contentDescription = stringResource(
                    if (state.isRecording) R.string.recorder_stop else R.string.recorder_start,
                ),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(48.dp),
            )
        }
    }
}
