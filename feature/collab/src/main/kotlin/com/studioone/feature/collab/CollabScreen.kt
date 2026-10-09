package com.studioone.feature.collab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studioone.core.designsystem.component.StudioBadge
import com.studioone.core.designsystem.component.StudioPrimaryButton
import com.studioone.core.designsystem.component.StudioSecondaryButton
import com.studioone.core.domain.model.collab.CollabConnectionState

/** Collaboration session screen: presence, invite link, activity, chat. */
@Composable
fun CollabScreen(
    modifier: Modifier = Modifier,
    viewModel: CollabViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var chatInput by remember { mutableStateOf("") }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.collab_title), style = MaterialTheme.typography.headlineSmall)

        val statusLabel = when (state.connection) {
            CollabConnectionState.CONNECTED -> stringResource(R.string.collab_connected)
            CollabConnectionState.CONNECTING, CollabConnectionState.RECONNECTING -> stringResource(R.string.collab_connecting)
            else -> stringResource(R.string.collab_disconnected)
        }
        StudioBadge(statusLabel)

        if (state.connection == CollabConnectionState.DISCONNECTED) {
            StudioPrimaryButton(
                text = stringResource(R.string.collab_join),
                onClick = { viewModel.join() },
            )
        }

        // Invite link.
        state.inviteLink?.let { link ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Link, contentDescription = null)
                Text(link, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                IconButton(onClick = { /* copy to clipboard */ }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.collab_invite))
                }
            }
        }

        // Participants.
        Text(stringResource(R.string.collab_participants), style = MaterialTheme.typography.titleMedium)
        state.participants.forEach { participant ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(participant.displayName, modifier = Modifier.weight(1f))
                if (participant.isHost) StudioBadge("host")
            }
        }

        // Activity feed.
        Text(stringResource(R.string.collab_activity), style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.activity) { entry ->
                Text(entry, style = MaterialTheme.typography.bodySmall)
            }
        }

        // Chat input.
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = chatInput,
                onValueChange = { chatInput = it },
                placeholder = { Text(stringResource(R.string.collab_chat_hint)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            StudioSecondaryButton(
                text = stringResource(R.string.collab_send),
                onClick = { viewModel.sendChat(chatInput); chatInput = "" },
            )
        }
    }
}
