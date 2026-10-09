package com.studioone.audio

import com.studioone.core.domain.model.EffectInstance
import com.studioone.core.domain.model.Track
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Application-level owner of the native engine. Serializes topology changes
 * behind a mutex and mirrors domain tracks into native track ids.
 *
 * The native engine is created lazily (first [start]/[play]/[record] call),
 * because opening a project mirrors the session graph into the engine before
 * any audio stream exists. Every graph mutation is therefore cached here and
 * re-applied the moment the engine comes up, and all calls made while the
 * engine is not yet created are harmless no-ops instead of crashes.
 */
@Singleton
class AudioEngineController @Inject constructor() {

    private val engine = AudioEngine()
    private val graphMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob())

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Domain track id -> native track id. */
    private val nativeTrackIds = HashMap<String, Int>()

    /** Last mirrored graph, re-applied whenever the engine (re)starts. */
    private var lastTracks: List<Track> = emptyList()
    private var lastTempo: Double = 120.0
    private val lastInserts = HashMap<String, List<EffectInstance>>()

    /** Creates the native engine (if needed), mirrors the cached graph, starts IO. */
    suspend fun start(config: AudioEngineConfig) {
        graphMutex.withLock { ensureStartedLocked(config) }
    }

    fun stop() {
        scope.launch {
            graphMutex.withLock {
                engine.stop()
                _running.value = false
            }
        }
    }

    fun syncTracks(tracks: List<Track>) {
        scope.launch {
            graphMutex.withLock {
                lastTracks = tracks
                if (engine.isNativeReady) applyTracksLocked(tracks)
            }
        }
    }

    fun applyEffects(domainTrackId: String, inserts: List<EffectInstance>) {
        scope.launch {
            graphMutex.withLock {
                lastInserts[domainTrackId] = inserts
                if (engine.isNativeReady) applyEffectsLocked(domainTrackId, inserts)
            }
        }
    }

    /** Live parameter tweak for one insert slot (mixer knob drags). */
    fun setEffectParameter(domainTrackId: String, insertIndex: Int, paramIndex: Int, value: Float) {
        scope.launch {
            graphMutex.withLock {
                val nativeId = nativeTrackIds[domainTrackId] ?: return@withLock
                engine.setEffectParameter(nativeId, insertIndex, paramIndex, value)
            }
        }
    }

    fun noteOn(domainTrackId: String, pitch: Int, velocity: Int) {
        nativeTrackIds[domainTrackId]?.let { engine.noteOn(it, pitch, velocity) }
    }

    fun noteOff(domainTrackId: String, pitch: Int) {
        nativeTrackIds[domainTrackId]?.let { engine.noteOff(it, pitch) }
    }

    fun play() {
        scope.launch {
            graphMutex.withLock {
                ensureStartedLocked(AudioEngineConfig())
                engine.setTransportState(TransportState.PLAYING)
            }
        }
    }

    fun stopTransport() {
        engine.setTransportState(TransportState.STOPPED)
    }

    fun record() {
        scope.launch {
            graphMutex.withLock {
                ensureStartedLocked(AudioEngineConfig(inputEnabled = true))
                engine.setTransportState(TransportState.RECORDING)
            }
        }
    }

    fun seek(frame: Long) = engine.seek(frame)
    fun playhead(): Long = engine.playhead()

    fun setTempo(bpm: Double) {
        scope.launch {
            graphMutex.withLock {
                lastTempo = bpm
                if (engine.isNativeReady) engine.setTempo(bpm)
            }
        }
    }

    fun setLoop(enabled: Boolean, start: Long, end: Long) = engine.setLoop(enabled, start, end)
    fun setMetronome(enabled: Boolean) = engine.setMetronome(enabled)

    /** Recording requires a live input stream; the engine is brought up first. */
    suspend fun startRecording(path: String, bitDepth: Int): Boolean = graphMutex.withLock {
        ensureStartedLocked(AudioEngineConfig(inputEnabled = true))
        engine.startRecording(path, bitDepth)
    }

    fun stopRecording() = engine.stopRecording()

    fun readMasterMeter(): FloatArray = engine.readMasterMeter()

    fun readTrackMeter(domainTrackId: String): FloatArray =
        nativeTrackIds[domainTrackId]?.let { engine.readTrackMeter(it) } ?: FloatArray(8)

    suspend fun outputLatencyMs(): Double = graphMutex.withLock { engine.outputLatencyMs() }

    // ---- internals (caller holds [graphMutex]) ------------------------------

    /** Creates the native engine and mirrors the cached graph, then starts IO. */
    private fun ensureStartedLocked(config: AudioEngineConfig) {
        if (_running.value) return
        engine.create()
        applyTracksLocked(lastTracks)
        engine.setTempo(lastTempo)
        lastInserts.forEach { (domainId, inserts) -> applyEffectsLocked(domainId, inserts) }
        _running.value = engine.start(config)
    }

    private fun applyTracksLocked(tracks: List<Track>) {
        val wanted = tracks.map { it.id.value }.toSet()
        // Remove natives whose domain track disappeared.
        nativeTrackIds.entries.removeAll { (domainId, nativeId) ->
            if (domainId !in wanted) {
                engine.removeTrack(nativeId)
                true
            } else false
        }
        // Add missing tracks.
        for (track in tracks) {
            if (track.id.value !in nativeTrackIds) {
                val kind = when (track.instrumentId) {
                    "drums" -> InstrumentKind.DRUM_SYNTH
                    "sampler" -> InstrumentKind.SAMPLER
                    else -> InstrumentKind.SUBTRACTIVE_SYNTH
                }
                val nativeId = engine.addTrack(track.type == com.studioone.core.domain.model.TrackType.MIDI, kind)
                if (nativeId > 0) nativeTrackIds[track.id.value] = nativeId
            }
            val nativeId = nativeTrackIds[track.id.value] ?: continue
            engine.setTrackBasic(nativeId, track.volume, track.pan, track.muted, track.soloed, track.armed)
        }
    }

    private fun applyEffectsLocked(domainTrackId: String, inserts: List<EffectInstance>) {
        val nativeId = nativeTrackIds[domainTrackId] ?: return
        inserts.forEachIndexed { index, instance ->
            engine.addEffect(nativeId, instance.type)
            instance.params.forEach { (paramId, value) ->
                val paramIndex = com.studioone.core.domain.model.fx.EffectCatalog.specs[instance.type]
                    ?.indexOfFirst { it.id == paramId } ?: -1
                if (paramIndex >= 0) {
                    engine.setEffectParameter(nativeId, index, paramIndex, value)
                }
            }
        }
    }
}
