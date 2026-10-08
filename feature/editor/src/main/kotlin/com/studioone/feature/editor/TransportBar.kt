package com.studioone.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.studioone.core.designsystem.theme.MonoTextStyle
import com.studioone.core.designsystem.theme.PlayGreen
import com.studioone.core.designsystem.theme.RecordRed
import com.studioone.core.designsystem.theme.SoloAmber
import com.studioone.core.common.util.formatTimecode

/** Transport: play/stop/record/loop + timecode readout + tempo display. */
@Composable
fun TransportBar(
    isPlaying: Boolean,
    isRecording: Boolean,
    loopEnabled: Boolean,
    playheadFrame: Long,
    tempo: Double,
    sampleRate: Int,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onRecord: () -> Unit,
    onToggleLoop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(tonalElevation = 2.dp, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onStop) {
                Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.editor_stop))
            }
            IconButton(onClick = onPlay) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.editor_play),
                    tint = if (isPlaying) PlayGreen else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = onRecord) {
                Icon(
                    Icons.Default.FiberManualRecord,
                    contentDescription = stringResource(R.string.editor_record),
                    tint = if (isRecording) RecordRed else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = onToggleLoop) {
                Icon(
                    Icons.Default.Repeat,
                    contentDescription = stringResource(R.string.editor_loop),
                    tint = if (loopEnabled) SoloAmber else MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(Modifier.weight(1f))

            Text(
                formatTimecode(playheadFrame, sampleRate),
                style = MonoTextStyle,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(16.dp))
            Text("%.2f BPM".format(tempo), style = MonoTextStyle)
        }
    }
}
