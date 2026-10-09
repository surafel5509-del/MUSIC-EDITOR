package com.studioone.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studioone.core.domain.model.TrackType

/**
 * The arrange workspace: transport bar on top, track headers + timeline below.
 * Mixer / instruments / library are sibling tabs owned by the app shell.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    onOpenMixer: () -> Unit,
    onOpenRecorder: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EditorViewModel = hiltViewModel(),
) {
    val editorState by viewModel.editorState.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var addTrackMenu by remember { mutableStateOf(false) }

    val state = editorState
    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(state?.project?.name ?: "", style = MaterialTheme.typography.titleMedium) },
                    actions = {
                        IconButton(onClick = { viewModel.undo() }, enabled = ui.undoRedo.canUndo) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.editor_undo))
                        }
                        IconButton(onClick = { viewModel.redo() }, enabled = ui.undoRedo.canRedo) {
                            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.editor_redo))
                        }
                        IconButton(onClick = { addTrackMenu = true }) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.editor_add_track))
                        }
                        DropdownMenu(expanded = addTrackMenu, onDismissRequest = { addTrackMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.editor_add_audio_track)) },
                                onClick = { addTrackMenu = false; viewModel.addTrack(TrackType.AUDIO) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.editor_add_midi_track)) },
                                onClick = { addTrackMenu = false; viewModel.addTrack(TrackType.MIDI) },
                            )
                        }
                    },
                )
                if (state != null) {
                    TransportBar(
                        isPlaying = state!!.isPlaying,
                        isRecording = state!!.isRecording,
                        loopEnabled = state!!.loopEnabled,
                        playheadFrame = state!!.playheadFrame,
                        tempo = state!!.project.tempo,
                        sampleRate = state!!.project.sampleRate,
                        onPlay = { viewModel.play() },
                        onStop = { viewModel.stop() },
                        onRecord = { viewModel.toggleRecord(); onOpenRecorder() },
                        onToggleLoop = { viewModel.toggleLoop() },
                    )
                }
            }
        },
    ) { padding ->
        if (state == null) {
            if (ui.loadError) {
                Box(
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.editor_load_error))
                }
            } else {
                com.studioone.core.designsystem.component.StudioLoadingView(Modifier.padding(padding))
            }
        } else {
            TimelinePane(
                state = state!!,
                zoom = ui.zoom,
                tool = ui.tool,
                onZoomChange = viewModel::setZoom,
                onToolChange = viewModel::setTool,
                onSelect = viewModel::selectClips,
                onMoveClips = viewModel::moveClips,
                onSplitClip = viewModel::splitClip,
                onDeleteSelected = viewModel::deleteSelected,
                onSeek = viewModel::seek,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}
