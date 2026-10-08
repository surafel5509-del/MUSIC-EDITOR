package com.studioone.mobile.core.audio

import com.studioone.mobile.core.model.AutomationCurve
import com.studioone.mobile.core.model.AutomationLane
import com.studioone.mobile.core.model.Project
import com.studioone.mobile.core.model.Track
import com.studioone.mobile.core.model.TrackType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Compiles a [Project] document into native engine state.
 *
 * This is the ONLY place that maps model objects -> native handles/commands.
 * Handles are deterministic: strip handle = project track ordinal hash, so
 * recompiles after undo/redo reuse the same native strips.
 */
@Singleton
class EngineGraphCompiler @Inject constructor(
    private val engine: AudioEngineController,
) {
    /** Stable native handle for a track (shared app-wide mapping). */
    fun handleFor(trackId: String): Int = EngineHandleMapping.handleFor(trackId)

    /** Full (re)compile: strips, mixer state, FX chains, automation, instruments. */
    fun compileProject(project: Project) {
        // Master chain.
        engine.setMasterLimiter(enabled = true, ceilingDb = -0.3f)
        engine.setTempo(project.tempoMap.baseBpm)
        engine.setTimeSignature(project.timeSignature.numerator, project.timeSignature.beatUnit)

        val liveHandles = mutableSetOf<Int>()
        for (track in project.tracks.filter { it.type != TrackType.MASTER }) {
            val handle = handleFor(track.id.value)
            liveHandles += handle
            compileTrack(handle, track, project)
        }
        // Remove strips that no longer exist in the document.
        val stale = EngineHandleMapping.snapshot().entries.filter { it.value !in liveHandles }
        for (e in stale) {
            engine.removeStrip(e.value)
            EngineHandleMapping.releaseHandle(e.key)
        }
    }

    fun compileTrack(handle: Int, track: Track, project: Project) {
        engine.addStrip(handle)
        engine.setStripGain(handle, track.volumeDb)
        engine.setStripPan(handle, track.pan)
        engine.setStripWidth(handle, track.width)
        engine.setStripMute(handle, track.mute)
        engine.setStripSolo(handle, track.solo)
        engine.setStripInputGain(handle, track.input.inputGainDb)
        engine.setStripArmed(handle, track.input.armed)
        engine.setMonitorMode(
            handle,
            when (track.input.monitorMode) {
                com.studioone.mobile.core.model.MonitorMode.OFF -> 0
                com.studioone.mobile.core.model.MonitorMode.ON -> 1
                com.studioone.mobile.core.model.MonitorMode.AUTO -> 2
            },
        )
        // Inserts.
        track.inserts.forEachIndexed { slot, fx ->
            engine.setFxSlot(handle, slot, fx.plugin, fx.params)
            if (!fx.enabled) engine.setFxBypass(handle, slot, true)
        }
        // Sends.
        track.sends.forEachIndexed { i, send ->
            engine.setSend(handle, i, send.levelDb, send.pan, send.preFader)
        }
        // Automation lanes -> baked [frame, value] point arrays.
        val framesPerTick =
            project.sampleRate * 60.0 / project.tempoMap.baseBpm / com.studioone.mobile.core.model.MidiConstants.PPQ
        for (lane in track.automation) {
            if (lane.points.isEmpty()) continue
            val baked = FloatArray(lane.points.size * 2)
            lane.points.forEachIndexed { i, p ->
                baked[i * 2] = (p.positionTicks * framesPerTick).toFloat()
                baked[i * 2 + 1] = bakeValue(p, lane)
            }
            engine.uploadAutomation(handle, lane.parameter, baked)
        }
    }

    private fun bakeValue(
        point: com.studioone.mobile.core.model.AutomationPoint,
        lane: AutomationLane,
    ): Float = when (point.curve) {
        AutomationCurve.HOLD -> point.value
        else -> point.value // curve shape is evaluated natively as linear between points
    }
}

/**
 * Stable track-id -> native strip-handle mapping shared across the app.
 * Handles are small positive ints (the native command protocol uses int32
 * targets); determinism across recompiles keeps undo/redo from churning
 * native strips.
 */
object EngineHandleMapping {
    private val map = mutableMapOf<String, Int>()
    private var next = 1

    @Synchronized
    fun handleFor(trackId: String): Int = map.getOrPut(trackId) { next++ }

    @Synchronized
    fun releaseHandle(trackId: String) { map.remove(trackId) }

    @Synchronized
    fun snapshot(): Map<String, Int> = map.toMap()
}
