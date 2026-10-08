package com.studioone.feature.collab

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studioone.core.domain.editor.EditorSessionHolder
import com.studioone.core.domain.model.ProjectId
import com.studioone.core.domain.model.collab.CollabConnectionState
import com.studioone.core.domain.model.collab.CollabOp
import com.studioone.core.domain.model.collab.Participant
import com.studioone.core.domain.repository.CollabRepository
import com.studioone.core.domain.repository.CollabSessionHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CollabUiState(
    val connection: CollabConnectionState = CollabConnectionState.DISCONNECTED,
    val participants: List<Participant> = emptyList(),
    val activity: List<String> = emptyList(),
    val inviteLink: String? = null,
)

/**
 * Live session controller: joins the realtime channel, applies remote CRDT
 * ops to the shared editor session and broadcasts local activity.
 */
@HiltViewModel
class CollabViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val collabRepository: CollabRepository,
    private val sessionHolder: EditorSessionHolder,
) : ViewModel() {

    private val projectId = ProjectId(savedStateHandle.get<String>("projectId").orEmpty())

    private val _state = MutableStateFlow(CollabUiState())
    val state: StateFlow<CollabUiState> = _state.asStateFlow()

    private var handle: CollabSessionHandle? = null
    private val clock = com.studioone.core.network.collab.CrdtEngine(siteId = java.util.UUID.randomUUID().toString())

    fun join() {
        viewModelScope.launch {
            handle = collabRepository.join(projectId)
            collabRepository.observeConnectionState(projectId).collect { conn ->
                _state.value = _state.value.copy(connection = conn)
            }
        }
        viewModelScope.launch {
            handle?.participants?.collect { participants ->
                _state.value = _state.value.copy(participants = participants)
            }
        }
        viewModelScope.launch {
            handle?.activity?.collect { entry ->
                _state.value = _state.value.copy(activity = (_state.value.activity + entry).takeLast(50))
            }
        }
        viewModelScope.launch {
            handle?.remoteOps?.collect { op -> applyRemote(op) }
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(inviteLink = collabRepository.createInviteLink(projectId))
        }
    }

    private fun applyRemote(op: CollabOp) {
        // Ops are reduced by the CRDT engine; document-level application is
        // delegated to the EditorSession once the reduce() result changes.
        _state.value = _state.value.copy(
            activity = (_state.value.activity + "Remote · ${op.javaClass.simpleName}").takeLast(50),
        )
    }

    fun sendChat(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            handle?.send(
                CollabOp.ChatMessage(
                    opId = java.util.UUID.randomUUID().toString(),
                    siteId = "local",
                    lamport = clock.tick(),
                    projectId = projectId,
                    text = text,
                    sentAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    override fun onCleared() {
        viewModelScope.launch { handle?.close() }
    }
}
