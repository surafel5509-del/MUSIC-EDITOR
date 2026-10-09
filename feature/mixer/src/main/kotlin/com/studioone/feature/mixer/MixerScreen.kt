package com.studioone.feature.mixer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studioone.core.designsystem.component.audio.Fader
import com.studioone.core.designsystem.component.audio.Knob
import com.studioone.core.designsystem.component.audio.LevelMeter
import com.studioone.core.designsystem.theme.MonoTextStyle
import com.studioone.core.designsystem.theme.MuteGray
import com.studioone.core.designsystem.theme.SoloAmber
import com.studioone.core.designsystem.theme.trackColor
import com.studioone.core.domain.model.Track

/**
 * Mixing console: horizontally scrolling channel strips + a master strip
 * with LUFS readouts. Meters are driven by the engine at 30fps.
 */
@Composable
fun MixerScreen(
    modifier: Modifier = Modifier,
    viewModel: MixerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()

    Row(
        modifier = modifier
            .fillMaxSize()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tracks.forEach { track ->
            ChannelStrip(
                track = track,
                meter = state.trackMeters[track.id.value] ?: MeterData(),
                onVolume = { gain, finish -> viewModel.setVolume(track.id, gain, finish) },
                onPan = { viewModel.setPan(track.id, it) },
                onToggleMute = { viewModel.toggleMute(track.id) },
                onToggleSolo = { viewModel.toggleSolo(track.id) },
                onOpenFx = { viewModel.selectTrack(track.id.value) },
            )
        }

        // Master strip.
        MasterStrip(meter = state.masterMeter)
    }

    if (state.showFxSheet && state.selectedTrackId != null) {
        FxChainSheet(
            track = tracks.firstOrNull { it.id.value == state.selectedTrackId },
            onAddEffect = { type ->
                tracks.firstOrNull { it.id.value == state.selectedTrackId }?.let {
                    viewModel.addEffect(it.id, type)
                }
            },
            onDismiss = { viewModel.selectTrack(null) },
        )
    }
}

@Composable
fun ChannelStrip(
    track: Track,
    meter: MeterData,
    onVolume: (Float, Boolean) -> Unit,
    onPan: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onToggleSolo: () -> Unit,
    onOpenFx: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.width(96.dp).fillMaxHeight(),
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                track.name,
                style = MaterialTheme.typography.labelMedium,
                color = trackColor(track.colorIndex),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))

            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Fader(
                    gain = track.volume,
                    onGainChange = { onVolume(it, false) },
                    onGestureEnd = { onVolume(track.volume, true) },
                    label = "${track.name} volume fader",
                )
                LevelMeter(
                    levelLeft = meter.rmsL,
                    levelRight = meter.rmsR,
                    peakLeft = meter.peakL,
                    peakRight = meter.peakR,
                    clipLeft = meter.clipL,
                    clipRight = meter.clipR,
                )
            }

            Knob(
                value = track.pan,
                range = -1f..1f,
                onValueChange = onPan,
                size = 40.dp,
                label = stringResource(R.string.mixer_pan),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MixToggle("M", track.muted, MuteGray, stringResource(R.string.mixer_mute), onToggleMute)
                MixToggle("S", track.soloed, SoloAmber, stringResource(R.string.mixer_solo), onToggleSolo)
            }

            com.studioone.core.designsystem.component.StudioTextButton(
                text = stringResource(R.string.mixer_inserts) +
                    if (track.inserts.isEmpty()) "" else " (${track.inserts.size})",
                onClick = onOpenFx,
            )
        }
    }
}

@Composable
private fun MixToggle(label: String, active: Boolean, color: androidx.compose.ui.graphics.Color, contentDescription: String, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(
        selected = active,
        onClick = onClick,
        label = { Text(label) },
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            selectedContainerColor = color.copy(alpha = 0.25f),
        ),
    )
}

@Composable
fun MasterStrip(meter: MeterData, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.width(120.dp).fillMaxHeight(),
        tonalElevation = 4.dp,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.mixer_master), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(12.dp))
            LevelMeter(
                levelLeft = meter.rmsL,
                levelRight = meter.rmsR,
                peakLeft = meter.peakL,
                peakRight = meter.peakR,
                clipLeft = meter.clipL,
                clipRight = meter.clipR,
                modifier = Modifier.weight(1f).width(24.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "${stringResource(R.string.mixer_lufs_momentary)}\n%.1f".format(meter.lufsMomentary),
                style = MonoTextStyle,
                textAlign = TextAlign.Center,
            )
            Text(
                "${stringResource(R.string.mixer_lufs_integrated)}\n%.1f".format(meter.lufsIntegrated),
                style = MonoTextStyle,
                textAlign = TextAlign.Center,
            )
        }
    }
}
