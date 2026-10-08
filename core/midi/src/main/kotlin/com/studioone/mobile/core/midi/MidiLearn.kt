package com.studioone.mobile.core.midi

import com.studioone.mobile.core.model.AutomatableParameter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** One learned mapping: MIDI CC -> engine parameter. */
data class MidiMapping(
    val ccNumber: Int,
    val channel: Int,              // -1 = any channel (omni learn)
    val deviceId: String?,         // null = any device
    val targetTrackHandle: Int,
    val parameter: AutomatableParameter,
    val fxSlot: Int = -1,          // >= 0 when the target is an FX param
    val fxParamIndex: Int = -1,
    val rangeMin: Float = 0f,
    val rangeMax: Float = 1f,
    val invert: Boolean = false,
) {
    /** Map a 0..1 CC value into the target range. */
    fun scale(value01: Float): Float {
        val v = if (invert) 1f - value01 else value01
        return rangeMin + (rangeMax - rangeMin) * v
    }
}

/**
 * MIDI Learn: user presses "learn" on a control, moves a knob, and the next
 * CC message binds to that control. Mappings persist per project
 * (ProjectDocument.midiMappings) and route through [MidiEngineRouter].
 */
@Singleton
class MidiLearnManager @Inject constructor() {

    private val _isLearning = MutableStateFlow(false)
    val isLearning: StateFlow<Boolean> = _isLearning.asStateFlow()

    private val _mappings = MutableStateFlow<List<MidiMapping>>(emptyList())
    val mappings: StateFlow<List<MidiMapping>> = _mappings.asStateFlow()

    private var pendingTarget: PendingTarget? = null

    data class PendingTarget(
        val targetTrackHandle: Int,
        val parameter: AutomatableParameter?,
        val fxSlot: Int,
        val fxParamIndex: Int,
        val rangeMin: Float,
        val rangeMax: Float,
    )

    fun startLearning(target: PendingTarget) {
        pendingTarget = target
        _isLearning.value = true
    }

    fun cancelLearning() {
        pendingTarget = null
        _isLearning.value = false
    }

    /** Feed every incoming message; returns true when a binding was created. */
    fun onMessage(msg: MidiMessage, deviceId: String): Boolean {
        if (!_isLearning.value) return false
        if (msg.type != MessageType.CONTROL_CHANGE) return false
        val target = pendingTarget ?: return false
        val mapping = MidiMapping(
            ccNumber = msg.data1,
            channel = -1, // omni by default; refine in the mapping editor
            deviceId = deviceId,
            targetTrackHandle = target.targetTrackHandle,
            parameter = target.parameter ?: AutomatableParameter.FX_PARAM_BASE,
            fxSlot = target.fxSlot,
            fxParamIndex = target.fxParamIndex,
            rangeMin = target.rangeMin,
            rangeMax = target.rangeMax,
        )
        _mappings.value = (_mappings.value.filterNot {
            it.ccNumber == mapping.ccNumber && it.channel == mapping.channel &&
                it.deviceId == mapping.deviceId && it.targetTrackHandle == mapping.targetTrackHandle &&
                it.fxSlot == mapping.fxSlot && it.fxParamIndex == mapping.fxParamIndex
        } + mapping)
        cancelLearning()
        return true
    }

    fun removeMapping(cc: Int, trackHandle: Int, fxSlot: Int, fxParamIndex: Int) {
        _mappings.value = _mappings.value.filterNot {
            it.ccNumber == cc && it.targetTrackHandle == trackHandle &&
                it.fxSlot == fxSlot && it.fxParamIndex == fxParamIndex
        }
    }

    fun replaceAll(mappings: List<MidiMapping>) {
        _mappings.value = mappings
    }

    /** Resolve all mappings for a message (CC number, optional channel filter). */
    fun resolve(msg: MidiMessage, deviceId: String): List<MidiMapping> {
        if (msg.type != MessageType.CONTROL_CHANGE) return emptyList()
        return _mappings.value.filter {
            it.ccNumber == msg.data1 &&
                (it.channel == -1 || it.channel == msg.channel) &&
                (it.deviceId == null || it.deviceId == deviceId)
        }
    }
}
