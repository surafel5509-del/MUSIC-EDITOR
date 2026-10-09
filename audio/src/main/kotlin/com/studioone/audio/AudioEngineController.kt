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

    fun start(config: AudioEngineConfig) {
        scope.launch {
            graphMutex.withLock {
                if (_running.value) return@withLock
                _running.value = engine.start(config)
            }
        }
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
        }
    }

    fun applyEffects(domainTrackId: String, inserts: List<EffectInstance>) {
        scope.launch {
            graphMutex.withLock {
                val nativeId = nativeTrackIds[domainTrackId] ?: return@withLock
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

    fun play() = engine.setTransportState(TransportState.PLAYING)
    fun stopTransport() {
        engine.setTransportState(TransportState.STOPPED)
    }
    fun record() = engine.setTransportState(TransportState.RECORDING)
    fun seek(frame: Long) = engine.seek(frame)
    fun playhead(): Long = engine.playhead()
    fun setTempo(bpm: Double) = engine.setTempo(bpm)
    fun setLoop(enabled: Boolean, start: Long, end: Long) = engine.setLoop(enabled, start, end)
    fun setMetronome(enabled: Boolean) = engine.setMetronome(enabled)

    fun startRecording(path: String, bitDepth: Int) = engine.startRecording(path, bitDepth)
    fun stopRecording() = engine.stopRecording()

    fun readMasterMeter(): FloatArray = engine.readMasterMeter()
    fun readTrackMeter(domainTrackId: String): FloatArray =
        nativeTrackIds[domainTrackId]?.let { engine.readTrackMeter(it) } ?: FloatArray(8)

    fun outputLatencyMs(): Double = engine.outputLatencyMs()
}
