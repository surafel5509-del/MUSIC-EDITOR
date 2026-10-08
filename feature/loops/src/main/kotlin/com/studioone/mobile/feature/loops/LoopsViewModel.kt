package com.studioone.mobile.feature.loops

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studioone.mobile.core.common.DataResult
import com.studioone.mobile.core.domain.DownloadProgress
import com.studioone.mobile.core.domain.LibraryRepository
import com.studioone.mobile.core.model.LibraryCategory
import com.studioone.mobile.core.model.LibraryGenre
import com.studioone.mobile.core.model.LibraryItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LoopsUiState(
    val items: List<LibraryItem> = emptyList(),
    val query: String = "",
    val category: LibraryCategory = LibraryCategory.LOOPS,
    val genre: LibraryGenre? = null,
    val bpmFilter: ClosedFloatingPointRange<Double>? = null,
    val isLoading: Boolean = false,
    val previewingItemId: String? = null,
    val downloads: Map<String, Float> = emptyMap(),
    val cursor: String? = null,
    val error: String? = null,
)

@HiltViewModel
class LoopsViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoopsUiState())
    val uiState: StateFlow<LoopsUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private val downloadJobs = mutableMapOf<String, Job>()

    init { search() }

    fun setQuery(q: String) {
        _uiState.value = _uiState.value.copy(query = q)
        // Debounced search.
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            search(reset = true)
        }
    }

    fun setCategory(category: LibraryCategory) {
        _uiState.value = _uiState.value.copy(category = category)
        search(reset = true)
    }

    fun setGenre(genre: LibraryGenre?) {
        _uiState.value = _uiState.value.copy(genre = genre)
        search(reset = true)
    }

    fun setBpmRange(range: ClosedFloatingPointRange<Double>?) {
        _uiState.value = _uiState.value.copy(bpmFilter = range)
        search(reset = true)
    }

    fun loadMore() {
        val cursor = _uiState.value.cursor ?: return
        search(reset = false, cursor = cursor)
    }

    fun download(item: LibraryItem) {
        if (downloadJobs.containsKey(item.id.value)) return
        downloadJobs[item.id.value] = viewModelScope.launch {
            libraryRepository.downloadItem(item.id.value).collect { progress: DownloadProgress ->
                _uiState.value = _uiState.value.copy(
                    downloads = _uiState.value.downloads + (item.id.value to progress.fraction),
                    items = _uiState.value.items.map {
                        if (it.id == item.id && progress.done) it.copy(isDownloaded = true, localUri = null) else it
                    },
                )
                if (progress.done || progress.error != null) downloadJobs.remove(item.id.value)
            }
        }
    }

    private fun search(reset: Boolean = true, cursor: String? = null) {
        viewModelScope.launch {
            val state = _uiState.value
            _uiState.value = state.copy(isLoading = true)
            val result = libraryRepository.search(
                query = state.query.ifBlank { null },
                category = state.category.name,
                genre = state.genre?.name,
                bpmRange = state.bpmFilter,
                key = null,
                cursor = cursor,
            )
            when (result) {
                is DataResult.Success -> _uiState.value = _uiState.value.copy(
                    items = if (reset) result.data.first else _uiState.value.items + result.data.first,
                    cursor = result.data.second,
                    isLoading = false,
                    error = null,
                )
                is DataResult.Failure -> _uiState.value = _uiState.value.copy(
                    isLoading = false, error = result.error.message)
                DataResult.Loading -> Unit
            }
        }
    }

    fun setPreviewing(itemId: String?) {
        _uiState.value = _uiState.value.copy(previewingItemId = itemId)
    }
}
