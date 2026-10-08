package com.studioone.mobile.feature.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studioone.mobile.core.common.DataResult
import com.studioone.mobile.core.domain.SocialRepository
import com.studioone.mobile.core.model.FeedFilter
import com.studioone.mobile.core.model.FeedTab
import com.studioone.mobile.core.model.Post
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FeedUiState(
    val posts: List<Post> = emptyList(),
    val filter: FeedFilter = FeedFilter(),
    val isLoading: Boolean = true,
    val cursor: String? = null,
    val playingPostId: String? = null,
)

@HiltViewModel
class SocialViewModel @Inject constructor(
    private val socialRepository: SocialRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    init { loadTab(FeedTab.FOR_YOU) }

    fun loadTab(tab: FeedTab) {
        _uiState.value = _uiState.value.copy(filter = _uiState.value.filter.copy(tab = tab), isLoading = true, posts = emptyList())
        load(reset = true)
    }

    fun loadMore() {
        val cursor = _uiState.value.cursor ?: return
        load(reset = false, cursor)
    }

    fun toggleLike(post: Post) {
        // Optimistic UI flip, reconciled by the repository.
        _uiState.value = _uiState.value.copy(
            posts = _uiState.value.posts.map {
                if (it.id == post.id) it.copy(
                    likedByMe = !it.likedByMe,
                    likeCount = it.likeCount + if (it.likedByMe) -1 else 1,
                ) else it
            },
        )
        viewModelScope.launch { socialRepository.like(post.id.value, !post.likedByMe) }
    }

    fun setPlaying(postId: String?) {
        _uiState.value = _uiState.value.copy(playingPostId = postId)
    }

    private fun load(reset: Boolean, cursor: String? = null) {
        viewModelScope.launch {
            val result = socialRepository.loadMoreFeed(_uiState.value.filter, cursor)
            when (result) {
                is DataResult.Success -> _uiState.value = _uiState.value.copy(
                    posts = if (reset) result.data.first else _uiState.value.posts + result.data.first,
                    cursor = result.data.second, isLoading = false,
                )
                is DataResult.Failure -> _uiState.value = _uiState.value.copy(isLoading = false)
                DataResult.Loading -> Unit
            }
        }
    }
}
