package com.studioone.mobile.feature.effects

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studioone.mobile.core.audio.AudioEngineController
import com.studioone.mobile.core.audio.EngineHandleMapping
import com.studioone.mobile.core.audio.nativeId
import com.studioone.mobile.core.common.IdGenerator
import com.studioone.mobile.core.domain.ProjectRepository
import com.studioone.mobile.core.model.FxPluginId
import com.studioone.mobile.core.model.FxPreset
import com.studioone.mobile.core.model.FxSlot
import com.studioone.mobile.core.model.FxSlotId
import com.studioone.mobile.core.model.PluginCategory
import com.studioone.mobile.core.model.ProjectId
import com.studioone.mobile.core.model.TrackId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FxRackUiState(
    val trackName: String = "",
    val slots: List<FxSlot> = emptyList(),
    val selectedSlot: Int = -1,
    val browseCategory: PluginCategory? = null,
    val showBrowser: Boolean = false,
    val savedPresets: List<FxPreset> = emptyList(),
)

/**
 * FX rack: the insert chain for one track/bus. Parameter specs come from
 * [FxParamCatalog] so the editor UI is generated, never hand-written per
 * plugin — adding a native plugin only requires catalog entries.
 */
@HiltViewModel
class FxRackViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val projectRepository: ProjectRepository,
    private val engine: AudioEngineController,
) : ViewModel() {

    private val projectId = ProjectId(savedStateHandle.get<String>("projectId")!!)
    private val trackId = TrackId(savedStateHandle.get<String>("trackId")!!)

    private val _uiState = MutableStateFlow(FxRackUiState())
    val uiState: StateFlow<FxRackUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            projectRepository.observeProject(projectId).collect { project ->
                val track = project?.track(trackId)
                _uiState.value = _uiState.value.copy(
                    trackName = track?.name ?: "",
                    slots = track?.inserts ?: emptyList(),
                )
            }
        }
    }

    fun selectSlot(index: Int) { _uiState.value = _uiState.value.copy(selectedSlot = index) }
    fun openBrowser(category: PluginCategory?) {
        _uiState.value = _uiState.value.copy(showBrowser = true, browseCategory = category)
    }
    fun closeBrowser() { _uiState.value = _uiState.value.copy(showBrowser = false) }

    fun addPlugin(plugin: FxPluginId) {
        val slots = _uiState.value.slots
        if (slots.size >= com.studioone.mobile.core.model.kMaxInsertSlotsUi) return
        val slot = FxSlot(
            id = FxSlotId(IdGenerator.newId()),
            plugin = plugin,
            params = FxParamCatalog.defaults(plugin),
        )
        val updated = slots + slot
        _uiState.value = _uiState.value.copy(slots = updated, selectedSlot = updated.size - 1, showBrowser = false)
        pushChain(updated)
    }

    fun removeSlot(index: Int) {
        val updated = _uiState.value.slots.filterIndexed { i, _ -> i != index }
        _uiState.value = _uiState.value.copy(slots = updated, selectedSlot = -1)
        pushChain(updated)
    }

    fun moveSlot(from: Int, to: Int) {
        val slots = _uiState.value.slots.toMutableList()
        if (from !in slots.indices || to !in slots.indices) return
        val item = slots.removeAt(from)
        slots.add(to, item)
        _uiState.value = _uiState.value.copy(slots = slots)
        engine.moveFxSlot(EngineHandleMapping.handleFor(trackId.value), from, to)
        persist(slots)
    }

    fun setParam(index: Int, slot: Int, value: Float) {
        val slots = _uiState.value.slots.toMutableList()
        if (slot !in slots.indices) return
        slots[slot] = slots[slot].copy(params = slots[slot].params + (index to value))
        _uiState.value = _uiState.value.copy(slots = slots)
        engine.setFxParam(EngineHandleMapping.handleFor(trackId.value), slot, index, value)
        persist(slots)
    }

    fun toggleBypass(slot: Int) {
        val slots = _uiState.value.slots.toMutableList()
        if (slot !in slots.indices) return
        slots[slot] = slots[slot].copy(enabled = !slots[slot].enabled)
        _uiState.value = _uiState.value.copy(slots = slots)
        engine.setFxBypass(EngineHandleMapping.handleFor(trackId.value), slot, !slots[slot].enabled)
        persist(slots)
    }

    fun setWetMix(slot: Int, wet: Float) {
        val slots = _uiState.value.slots.toMutableList()
        if (slot !in slots.indices) return
        slots[slot] = slots[slot].copy(wetMix = wet)
        _uiState.value = _uiState.value.copy(slots = slots)
        pushChain(slots)
    }

    fun savePreset(name: String) {
        val slot = _uiState.value.slots.getOrNull(_uiState.value.selectedSlot) ?: return
        val preset = FxPreset(
            id = com.studioone.mobile.core.model.PresetId(IdGenerator.newId()),
            plugin = slot.plugin,
            name = name,
            params = slot.params,
            isFactory = false,
        )
        _uiState.value = _uiState.value.copy(savedPresets = _uiState.value.savedPresets + preset)
        // Persisted through PresetRepository in the full flow (Room presets table).
    }

    private fun pushChain(slots: List<FxSlot>) {
        val handle = EngineHandleMapping.handleFor(trackId.value)
        slots.forEachIndexed { index, slot ->
            engine.setFxSlot(handle, index, slot.plugin, slot.params)
            if (!slot.enabled) engine.setFxBypass(handle, index, true)
        }
        // Clear any trailing slots.
        for (i in slots.size until com.studioone.mobile.core.model.kMaxInsertSlotsUi) {
            engine.setFxSlot(handle, i, null)
        }
        persist(slots)
    }

    private fun persist(slots: List<FxSlot>) {
        viewModelScope.launch {
            val project = projectRepository.getProject(projectId).getOrNull() ?: return@launch
            val updated = project.copy(
                tracks = project.tracks.map { if (it.id == trackId) it.copy(inserts = slots) else it },
            )
            projectRepository.saveProject(updated)
        }
    }
}
