package com.studioone.mobile.feature.export

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studioone.mobile.core.common.DataResult
import com.studioone.mobile.core.domain.CheckEntitlement
import com.studioone.mobile.core.domain.ExportRepository
import com.studioone.mobile.core.domain.ProjectRepository
import com.studioone.mobile.core.model.AudioMetadata
import com.studioone.mobile.core.model.ExportChannels
import com.studioone.mobile.core.model.ExportFormat
import com.studioone.mobile.core.model.ExportKind
import com.studioone.mobile.core.model.ExportProgress
import com.studioone.mobile.core.model.ExportSettings
import com.studioone.mobile.core.model.ProjectId
import com.studioone.mobile.core.model.TrackId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ExportUiState(
    val projectName: String = "",
    val settings: ExportSettings = ExportSettings(),
    val progress: ExportProgress? = null,
    val isExporting: Boolean = false,
    val outputUri: String? = null,
    val entitlementError: String? = null,
    val stemTrackIds: Set<String> = emptySet(),
    val availableTrackIds: List<Pair<String, String>> = emptyList(),
)

@HiltViewModel
class ExportViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val exportRepository: ExportRepository,
    private val projectRepository: ProjectRepository,
    private val checkEntitlement: CheckEntitlement,
) : ViewModel() {

    private val projectId = ProjectId(savedStateHandle.get<String>("projectId")!!)
    private val _uiState = MutableStateFlow(ExportUiState())
    val uiState: StateFlow<ExportUiState> = _uiState.asStateFlow()
    private var exportJob: Job? = null

    init {
        viewModelScope.launch {
            val project = projectRepository.getProject(projectId).getOrNull() ?: return@launch
            _uiState.value = _uiState.value.copy(
                projectName = project.name,
                availableTrackIds = project.tracks.map { it.id.value to it.name },
                settings = _uiState.value.settings.copy(
                    metadata = AudioMetadata(
                        title = project.name,
                        bpm = project.tempoMap.baseBpm,
                        initialKey = project.key.toString(),
                        genre = project.genre,
                    ),
                ),
            )
        }
    }

    fun setKind(kind: ExportKind) { _uiState.value = _uiState.value.copy(settings = _uiState.value.settings.copy(kind = kind)) }
    fun setFormat(format: ExportFormat) { _uiState.value = _uiState.value.copy(settings = _uiState.value.settings.copy(format = format)) }
    fun setBitrate(kbps: Int) { _uiState.value = _uiState.value.copy(settings = _uiState.value.settings.copy(bitrateKbps = kbps)) }
    fun setSampleRate(rate: Int) { _uiState.value = _uiState.value.copy(settings = _uiState.value.settings.copy(sampleRate = rate)) }
    fun setChannels(channels: ExportChannels) { _uiState.value = _uiState.value.copy(settings = _uiState.value.settings.copy(channels = channels)) }
    fun setNormalize(on: Boolean) { _uiState.value = _uiState.value.copy(settings = _uiState.value.settings.copy(normalize = on)) }
    fun setLoudnessTarget(lufs: Float?) { _uiState.value = _uiState.value.copy(settings = _uiState.value.settings.copy(loudnessTargetLUFS = lufs)) }
    fun setMetadata(meta: AudioMetadata) { _uiState.value = _uiState.value.copy(settings = _uiState.value.settings.copy(metadata = meta)) }
    fun toggleStemTrack(trackId: String) {
        val set = _uiState.value.stemTrackIds
        _uiState.value = _uiState.value.copy(
            stemTrackIds = if (trackId in set) set - trackId else set + trackId,
        )
    }

    fun startExport() {
        val state = _uiState.value
        viewModelScope.launch {
            val check = checkEntitlement.canExport(state.settings)
            if (check is DataResult.Failure) {
                _uiState.value = state.copy(entitlementError = check.error.message)
                return@launch
            }
            _uiState.value = _uiState.value.copy(isExporting = true, entitlementError = null, progress = null)
            exportJob?.cancel()
            val flow = if (state.settings.kind == ExportKind.STEMS) {
                exportRepository.exportStems(projectId, state.settings, state.stemTrackIds.map(::TrackId))
            } else {
                exportRepository.exportProject(projectId, state.settings)
            }
            exportJob = viewModelScope.launch {
                flow.collect { progress ->
                    _uiState.value = _uiState.value.copy(progress = progress)
                    if (progress.phase == com.studioone.mobile.core.model.ExportPhase.COMPLETE) {
                        _uiState.value = _uiState.value.copy(isExporting = false, outputUri = "media-store")
                    }
                    if (progress.phase == com.studioone.mobile.core.model.ExportPhase.FAILED) {
                        _uiState.value = _uiState.value.copy(isExporting = false)
                    }
                }
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        _uiState.value = _uiState.value.copy(isExporting = false)
    }
}
