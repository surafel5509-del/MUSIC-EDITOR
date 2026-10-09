package com.studioone.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studioone.core.domain.model.library.LoopItem
import com.studioone.core.domain.repository.LibraryFilter
import com.studioone.core.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LibraryUiState(
    val items: List<LoopItem> = emptyList(),
    val filter: LibraryFilter = LibraryFilter(),
    val previewingId: String? = null,
    val isLoading: Boolean = true,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val previewPlayer: PreviewPlayer,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    /** Emits when a loop is confirmed for insertion into the timeline. */
    val addToProjectEvents = MutableSharedFlow<LoopItem>(extraBufferCapacity = 4)

    init {
        observe()
    }

    fun search(query: String) {
        _state.value = _state.value.copy(filter = _state.value.filter.copy(query = query))
        observe()
    }

    fun toggleFavorite(item: LoopItem) {
        viewModelScope.launch { libraryRepository.toggleFavorite(item) }
    }

    fun preview(item: LoopItem) {
        val path = item.localPath ?: return
        viewModelScope.launch {
            if (_state.value.previewingId == item.id) {
                previewPlayer.stop()
                _state.value = _state.value.copy(previewingId = null)
            } else {
                previewPlayer.play(path)
                _state.value = _state.value.copy(previewingId = item.id)
            }
        }
    }

    fun addToProject(item: LoopItem) {
        viewModelScope.launch { addToProjectEvents.emit(item) }
    }

    fun importUri(uri: String, name: String) {
        viewModelScope.launch {
            val item = libraryRepository.importLocalFile(uri, name)
            val analyzed = runCatching { libraryRepository.analyze(item) }.getOrDefault(item)
            _state.value = _state.value.copy()
        }
    }

    private fun observe() {
        viewModelScope.launch {
            libraryRepository.observeItems(_state.value.filter).collect { items ->
                _state.value = _state.value.copy(items = items, isLoading = false)
            }
        }
    }

    override fun onCleared() {
        previewPlayer.release()
    }
}
