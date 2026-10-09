package com.studioone.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.studioone.core.domain.model.Track
import com.studioone.core.domain.model.TrackType
import com.studioone.core.designsystem.theme.ArmedRed
import com.studioone.core.designsystem.theme.MuteGray
import com.studioone.core.designsystem.theme.SoloAmber
import com.studioone.core.designsystem.theme.trackColor

/**
 * Left-side track strip: color tab, name, arm/mute/solo toggles and a mini
 * volume readout. Fixed-width column paired with the timeline lanes.
 */
@Composable
fun TrackHeader(
    track: Track,
    height: Dp,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().height(height),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(
                Modifier
                    .width(4.dp)
                    .height(height - 24.dp)
                    .background(trackColor(track.colorIndex), RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (track.type == TrackType.AUDIO) "Audio" else track.instrumentId ?: "Instrument",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToggleChip(
                        label = "R",
                        active = track.armed,
                        activeColor = ArmedRed,
                        contentDescription = stringResource(R.string.editor_cd_arm),
                        onClick = { /* session.updateTrack arm */ },
                    )
                    ToggleChip(
                        label = "M",
                        active = track.muted,
                        activeColor = MuteGray,
                        contentDescription = stringResource(R.string.editor_cd_mute),
                        onClick = { /* session.updateTrack mute */ },
                    )
                    ToggleChip(
                        label = "S",
                        active = track.soloed,
                        activeColor = SoloAmber,
                        contentDescription = stringResource(R.string.editor_cd_solo),
                        onClick = { /* session.updateTrack solo */ },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleChip(
    label: String,
    active: Boolean,
    activeColor: Color,
    contentDescription: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.size(28.dp).semantics { this.contentDescription = contentDescription },
        shape = CircleShape,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) activeColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
    }
}
