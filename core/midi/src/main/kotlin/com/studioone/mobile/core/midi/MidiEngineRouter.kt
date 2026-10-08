package com.studioone.mobile.core.midi

import com.studioone.mobile.core.audio.AudioEngineController
import javax.inject.Inject
import javax.inject.Singleton

/** Route mapping: which strip receives notes from which device/channel. */
data class MidiInputRoute(
    val deviceId: String?,       // null = all devices
    val channel: Int,            // -1 = omni
    val stripHandle: Int,
    val transposeOctaves: Int = 0,
    val velocityScale: Float = 1f,
)

/**
 * Bridges MIDI devices + learn mappings into the native engine:
 *  * note/CC/bend/pressure messages -> engine MIDI queue (sample-offset 0;
 *    live playing latency is dominated by the audio buffer, offsets matter
 *    only for sequenced playback which runs inside the native arpeggiator).
 *  * CC messages first pass through [MidiLearnManager] bindings and are
 *    translated to engine parameter commands.
 */
@Singleton
class MidiEngineRouter @Inject constructor(
    private val engine: AudioEngineController,
    private val learnManager: MidiLearnManager,
    deviceManager: MidiDeviceManager,
) {
    @Volatile
    var routes: List<MidiInputRoute> = emptyList()

    init {
        deviceManager.setMessageRouter { msg, deviceId -> onMessage(msg, deviceId) }
    }

    fun onMessage(msg: MidiMessage, deviceId: String) {
        // 1. Learn binding consumes CCs first.
        if (learnManager.onMessage(msg, deviceId)) return

        // 2. Mapped CCs -> parameter commands.
        if (msg.type == MessageType.CONTROL_CHANGE) {
            val value01 = msg.data2 / 127f
            for (mapping in learnManager.resolve(msg, deviceId)) {
                val scaled = mapping.scale(value01)
                if (mapping.fxSlot >= 0) {
                    engine.setFxParam(mapping.targetTrackHandle, mapping.fxSlot, mapping.fxParamIndex, scaled)
                } else if (mapping.parameter != null) {
                    when (mapping.parameter) {
                        com.studioone.mobile.core.model.AutomatableParameter.TRACK_VOLUME ->
                            engine.setStripGain(mapping.targetTrackHandle, scaled * 12f - 60f)
                        com.studioone.mobile.core.model.AutomatableParameter.TRACK_PAN ->
                            engine.setStripPan(mapping.targetTrackHandle, scaled * 2f - 1f)
                        else -> engine.uploadAutomation(mapping.targetTrackHandle, mapping.parameter, floatArrayOf())
                    }
                }
            }
        }

        // 3. Note & expression routing to instrument strips.
        for (route in routes) {
            if (route.deviceId != null && route.deviceId != deviceId) continue
            if (route.channel != -1 && route.channel != msg.channel) continue
            when (msg.type) {
                MessageType.NOTE_ON -> {
                    val velocity = if (msg.data2 == 0) 0 else
                        (msg.data2 * route.velocityScale).toInt().coerceIn(1, 127)
                    val key = (msg.data1 + route.transposeOctaves * 12).coerceIn(0, 127)
                    if (velocity == 0) engine.sendMidiNoteOff(route.stripHandle, key, msg.channel)
                    else engine.sendMidiNoteOn(route.stripHandle, key, velocity, msg.channel)
                }
                MessageType.NOTE_OFF ->
                    engine.sendMidiNoteOff(
                        route.stripHandle,
                        (msg.data1 + route.transposeOctaves * 12).coerceIn(0, 127),
                        msg.channel,
                    )
                MessageType.PITCH_BEND ->
                    engine.sendMidiPitchBend(route.stripHandle, MidiMessage.bendValue(msg) - 8192, msg.channel)
                MessageType.CHANNEL_PRESSURE ->
                    engine.sendMidiPressure(route.stripHandle, msg.data1 / 127f, msg.channel)
                MessageType.CONTROL_CHANGE ->
                    engine.sendMidiCc(route.stripHandle, msg.data1, msg.data2 / 127f, msg.channel)
                else -> Unit
            }
        }
    }
}
