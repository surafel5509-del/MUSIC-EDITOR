package com.studioone.mobile.feature.collab

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studioone.mobile.core.common.DataResult
import com.studioone.mobile.core.common.IdGenerator
import com.studioone.mobile.core.domain.AuthRepository
import com.studioone.mobile.core.domain.CollaborationRepository
import com.studioone.mobile.core.model.Comment
import com.studioone.mobile.core.model.CommentId
import com.studioone.mobile.core.model.ProjectId
import com.studioone.mobile.core.model.ProjectRole
import com.studioone.mobile.core.model.ShareLink
import com.studioone.mobile.core.model.UserId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

data class CollabUiState(
    val comments: List<Comment> = emptyList(),
    val newCommentText: String = "",
    val anchorFrame: Long? = null,
    val shareLink: ShareLink? = null,
    val shareRole: ProjectRole = ProjectRole.COMMENTER,
    val isCreatingLink: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class CollabViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val collaborationRepository: CollaborationRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val projectId = ProjectId(savedStateHandle.get<String>("projectId")!!)
    private val _uiState = MutableStateFlow(CollabUiState())
    val uiState: StateFlow<CollabUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            collaborationRepository.observeComments(projectId).collect { comments ->
                _uiState.value = _uiState.value.copy(comments = comments)
            }
        }
    }

    fun setCommentText(text: String) { _uiState.value = _uiState.value.copy(newCommentText = text) }
    fun setAnchor(frame: Long?) { _uiState.value = _uiState.value.copy(anchorFrame = frame) }
    fun setShareRole(role: ProjectRole) { _uiState.value = _uiState.value.copy(shareRole = role) }

    fun submitComment() {
        val state = _uiState.value
        val text = state.newCommentText.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            val me = authRepository.currentUserOnce()
            val comment = Comment(
                id = CommentId(IdGenerator.newId()),
                projectId = projectId,
                authorId = me?.id ?: UserId("guest"),
                authorName = me?.displayName ?: "Guest",
                text = text,
                anchorFrame = state.anchorFrame,
                createdAt = Clock.System.now(),
            )
            collaborationRepository.addComment(comment)
            _uiState.value = _uiState.value.copy(newCommentText = "", anchorFrame = null)
        }
    }

    fun resolve(comment: Comment) {
        viewModelScope.launch {
            collaborationRepository.resolveComment(comment.id.value, !comment.resolved)
        }
    }

    fun createShareLink() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCreatingLink = true)
            val result = collaborationRepository.createShareLink(
                projectId, _uiState.value.shareRole, forkOnAccept = false, expiresHours = 72,
            )
            _uiState.value = _uiState.value.copy(
                isCreatingLink = false,
                shareLink = (result as? DataResult.Success)?.data,
                message = if (result is DataResult.Failure) result.error.message else null,
            )
        }
    }
}
