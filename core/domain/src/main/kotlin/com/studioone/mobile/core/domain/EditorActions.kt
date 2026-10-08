package com.studioone.mobile.core.domain

import com.studioone.mobile.core.common.DataResult
import com.studioone.mobile.core.common.IdGenerator
import com.studioone.mobile.core.model.AudioClip
import com.studioone.mobile.core.model.AudioFileRef
import com.studioone.mobile.core.model.Chord
import com.studioone.mobile.core.model.Clip
import com.studioone.mobile.core.model.ClipId
import com.studioone.mobile.core.model.Fade
import com.studioone.mobile.core.model.MidiClip
import com.studioone.mobile.core.model.MidiNote
import com.studioone.mobile.core.model.Project
import com.studioone.mobile.core.model.ProjectId
import com.studioone.mobile.core.model.TimeSignature
import com.studioone.mobile.core.model.Track
import com.studioone.mobile.core.model.TrackColor
import com.studioone.mobile.core.model.TrackId
import com.studioone.mobile.core.model.TrackType
import kotlinx.datetime.Clock

/**
 * Pure editor operations on [Project] documents. Every function returns a new
 * immutable project (or DataResult.Failure when the edit is illegal, e.g.
 * dropping a clip on a frozen track). Feature ViewModels compose these with
 * [UndoRedoManager] + repository persistence — the classic MVI reducer core.
 *
 * These are the SAME operations the CRDT layer broadcasts: each maps 1:1 to a
 * CollabOp (see core:data ProjectEditor which wraps them + submits ops).
 */
object EditorActions {

    fun addTrack(
        project: Project,
        type: TrackType = TrackType.AUDIO,
        name: String? = null,
    ): Project {
        val index = project.tracks.size
        val track = Track(
            id = TrackId(IdGenerator.newId()),
            name = name ?: defaultTrackName(type, index),
            type = type,
            color = TrackColor.entries[index % TrackColor.entries.size],
            orderIndex = index,
            instrument = if (type == TrackType.INSTRUMENT) {
                com.studioone.mobile.core.model.InstrumentInstance(
                    instrument = com.studioone.mobile.core.model.InstrumentId.SUBTRACTIVE_SYNTH,
                    presetId = com.studioone.mobile.core.model.PresetId("volt1-init"),
                )
            } else null,
        )
        return project.copy(tracks = project.tracks + track)
    }

    private fun defaultTrackName(type: TrackType, index: Int): String = when (type) {
        TrackType.AUDIO -> "Audio ${index + 1}"
        TrackType.MIDI -> "MIDI ${index + 1}"
        TrackType.INSTRUMENT -> "Instrument ${index + 1}"
        TrackType.BUS -> "Bus ${index + 1}"
        TrackType.AUX_RETURN -> "Return ${index + 1}"
        TrackType.MASTER -> "Master"
    }

    fun removeTrack(project: Project, trackId: TrackId): Project =
        project.copy(tracks = project.tracks.filterNot { it.id == trackId }
            .mapIndexed { i, t -> t.copy(orderIndex = i) })

    fun updateTrack(project: Project, trackId: TrackId, transform: (Track) -> Track): Project =
        project.copy(tracks = project.tracks.map { if (it.id == trackId) transform(it) else it })

    fun addClip(project: Project, trackId: TrackId, clip: Clip): DataResult<Project> {
        val track = project.track(trackId) ?: return DataResult.Failure(
            com.studioone.mobile.core.common.DataError(com.studioone.mobile.core.common.DataError.Kind.NOT_FOUND, "Track missing"))
        if (track.frozen) return DataResult.Failure(
            com.studioone.mobile.core.common.DataError(com.studioone.mobile.core.common.DataError.Kind.PERMISSION, "Unfreeze the track first"))
        val typeOk = (track.type == TrackType.MIDI || track.type == TrackType.INSTRUMENT) == (clip is MidiClip)
        if (!typeOk) return DataResult.Failure(
            com.studioone.mobile.core.common.DataError(com.studioone.mobile.core.common.DataError.Kind.PERMISSION, "Clip type doesn't match track"))
        return DataResult.Success(updateTrack(project, trackId) {
            it.copy(clips = (it.clips + clip).sortedBy { c -> c.startFrame })
        })
    }

    fun removeClip(project: Project, trackId: TrackId, clipId: ClipId): Project =
        updateTrack(project, trackId) { t -> t.copy(clips = t.clips.filterNot { it.id == clipId }) }

    fun updateClip(project: Project, trackId: TrackId, clipId: ClipId, transform: (Clip) -> Clip): Project =
        updateTrack(project, trackId) { t ->
            t.copy(clips = t.clips.map { if (it.id == clipId) transform(it) else it })
        }

    /** Move a clip; when [ripple] is true, later clips on the track shift by the same delta. */
    fun moveClip(project: Project, trackId: TrackId, clipId: ClipId, newStartFrame: Long, ripple: Boolean = false): Project {
        val track = project.track(trackId) ?: return project
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return project
        val delta = newStartFrame - clip.startFrame
        return updateTrack(project, trackId) { t ->
            val moved = t.clips.map { c ->
                when {
                    c.id == clipId -> c.withPosition(newStartFrame.coerceAtLeast(0))
                    ripple && c.startFrame > clip.startFrame -> c.withPosition((c.startFrame + delta).coerceAtLeast(0))
                    else -> c
                }
            }
            t.copy(clips = moved.sortedBy { it.startFrame })
        }
    }

    /** Scissor split at an absolute frame position. */
    fun splitClip(project: Project, trackId: TrackId, clipId: ClipId, atFrame: Long, defaultFadeFrames: Long = 64): DataResult<Project> {
        val track = project.track(trackId) ?: return DataResult.Failure(
            com.studioone.mobile.core.common.DataError(com.studioone.mobile.core.common.DataError.Kind.NOT_FOUND))
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return DataResult.Failure(
            com.studioone.mobile.core.common.DataError(com.studioone.mobile.core.common.DataError.Kind.NOT_FOUND))
        if (atFrame <= clip.startFrame || atFrame >= clip.endFrame) {
            return DataResult.Failure(com.studioone.mobile.core.common.DataError(
                com.studioone.mobile.core.common.DataError.Kind.PERMISSION, "Split point outside clip"))
        }
        val fade = Fade(fadeInFrames = defaultFadeFrames, fadeOutFrames = defaultFadeFrames)
        val left = when (clip) {
            is AudioClip -> clip.copy(
                lengthFrames = atFrame - clip.startFrame,
                fade = clip.fade.copy(fadeOutFrames = defaultFadeFrames),
            )
            is MidiClip -> {
                // Note ticks are clip-relative; convert the split frame to a
                // clip-relative tick for the note surgery.
                val grid = GridMath(project.tempoMap, project.timeSignature, project.sampleRate)
                val atTickRel = grid.framesToTicks(atFrame) - grid.framesToTicks(clip.startFrame)
                clip.copy(
                    lengthFrames = atFrame - clip.startFrame,
                    notes = clip.notes.mapNotNull { n ->
                        when {
                            n.startTick + n.durationTicks <= atTickRel -> n
                            n.startTick >= atTickRel -> null
                            else -> n.copy(durationTicks = atTickRel - n.startTick)
                        }
                    },
                )
            }
        }
        val right = when (clip) {
            is AudioClip -> clip.copy(
                id = ClipId(IdGenerator.newId()),
                startFrame = atFrame,
                lengthFrames = clip.endFrame - atFrame,
                sourceOffsetFrames = clip.sourceOffsetFrames + (atFrame - clip.startFrame),
                fade = fade,
            )
            is MidiClip -> {
                val grid = GridMath(project.tempoMap, project.timeSignature, project.sampleRate)
                val atTick = grid.framesToTicks(atFrame) - grid.framesToTicks(clip.startFrame)
                clip.copy(
                    id = ClipId(IdGenerator.newId()),
                    startFrame = atFrame,
                    lengthFrames = clip.endFrame - atFrame,
                    notes = clip.notes.mapNotNull { n ->
                        if (n.startTick >= atTick) n.copy(startTick = n.startTick - atTick)
                        else if (n.endTick > atTick) n.copy(durationTicks = n.endTick - atTick, startTick = 0)
                        else null
                    },
                    fade = Fade(),
                )
            }
        }
        val updated = updateTrack(project, trackId) { t ->
            val others = t.clips.filterNot { it.id == clipId }
            t.copy(clips = (others + left + right).sortedBy { it.startFrame })
        }
        return DataResult.Success(updated)
    }

    /** Ripple delete: remove the clip and close the gap. */
    fun rippleDelete(project: Project, trackId: TrackId, clipId: ClipId): Project {
        val track = project.track(trackId) ?: return project
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return project
        return updateTrack(project, trackId) { t ->
            val remaining = t.clips.filterNot { it.id == clipId }.map {
                if (it.startFrame > clip.startFrame) it.withPosition(it.startFrame - clip.lengthFrames) else it
            }
            t.copy(clips = remaining.sortedBy { c -> c.startFrame })
        }
    }

    /** Build an audio clip from an imported sample, tempo-matched when possible. */
    fun clipFromSample(
        sample: AudioFileRef, project: Project, atFrame: Long, name: String? = null,
    ): AudioClip {
        // Tempo matching for loops is applied by the importer (it re-caches a
        // stretched PCM when sample.bpm != project tempo and the user opted in).
        return AudioClip(
            id = ClipId(IdGenerator.newId()),
            startFrame = atFrame,
            lengthFrames = sample.durationFrames,
            name = name ?: sample.uri.substringAfterLast('/'),
            fileRef = sample,
            fade = Fade(fadeInFrames = 32, fadeOutFrames = 128),
        )
    }

    /** Insert a chord as a MIDI clip's notes (chord editor "insert chord"). */
    fun insertChord(clip: MidiClip, chord: Chord, startTick: Long, durationTicks: Long, rootOctaveKey: Int = 48, velocity: Int = 100): MidiClip {
        val nextId = (clip.notes.maxOfOrNull { it.id } ?: 0) + 1
        val newNotes = chord.voicedKeys(rootOctaveKey).mapIndexed { i, key ->
            MidiNote(id = nextId + i, startTick = startTick, durationTicks = durationTicks, key = key.coerceIn(0, 127), velocity = velocity)
        }
        return clip.copy(notes = (clip.notes + newNotes).sortedBy { it.startTick })
    }

    /** Normalize an audio clip's gain to [targetPeakDb] using known peaks. */
    fun normalizeClip(project: Project, trackId: TrackId, clipId: ClipId, targetPeakDb: Float = -0.1f): Project {
        val track = project.track(trackId) ?: return project
        val clip = track.clips.firstOrNull { it.id == clipId } as? AudioClip ?: return project
        val peaks = clip.fileRef.peaks ?: return project
        var max = 0f
        for (b in 0 until peaks.bucketCount) for (c in 0 until peaks.channelCount) {
            val v = kotlin.math.abs(peaks.maxAt(b, c))
            if (v > max) max = v
        }
        if (max <= 1e-6f) return project
        val targetLin = Math.pow(10.0, targetPeakDb / 20.0).toFloat()
        val gainDb = 20f * kotlin.math.log10(targetLin / max)
        return updateClip(project, trackId, clipId) { (it as AudioClip).copy(gainDb = it.gainDb + gainDb, normalized = true) }
    }

    /** Reverse flag toggle (rendered by the feeder/offline bounce). */
    fun reverseClip(project: Project, trackId: TrackId, clipId: ClipId): Project =
        updateClip(project, trackId, clipId) {
            if (it is AudioClip) it.copy(reverse = !it.reverse) else it
        }

    /** Create the default track set for a template (used by CreateProject). */
    fun templateTracks(template: com.studioone.mobile.core.model.ProjectTemplate): List<Track> = when (template) {
        com.studioone.mobile.core.model.ProjectTemplate.BEAT -> listOf(
            drumTrack("Drums"), bassTrack("808"),
            Track(IdGenerator.newId().let { TrackId(it) }, "Keys", TrackType.INSTRUMENT, TrackColor.INDIGO, 2),
            Track(TrackId(IdGenerator.newId()), "Melody", TrackType.INSTRUMENT, TrackColor.TEAL, 3),
        )
        com.studioone.mobile.core.model.ProjectTemplate.SONG -> listOf(
            Track(TrackId(IdGenerator.newId()), "Lead Vocal", TrackType.AUDIO, TrackColor.ROSE, 0,
                input = com.studioone.mobile.core.model.InputRouting(armed = true)),
            Track(TrackId(IdGenerator.newId()), "Guitar", TrackType.AUDIO, TrackColor.AMBER, 1),
            Track(TrackId(IdGenerator.newId()), "Keys", TrackType.INSTRUMENT, TrackColor.INDIGO, 2),
            drumTrack("Drums"),
        )
        com.studioone.mobile.core.model.ProjectTemplate.PODCAST -> listOf(
            Track(TrackId(IdGenerator.newId()), "Host", TrackType.AUDIO, TrackColor.SKY, 0,
                input = com.studioone.mobile.core.model.InputRouting(armed = true),
                inserts = listOf(podcastVoiceChain())),
            Track(TrackId(IdGenerator.newId()), "Guest", TrackType.AUDIO, TrackColor.LIME, 1,
                inserts = listOf(podcastVoiceChain())),
            Track(TrackId(IdGenerator.newId()), "Music Bed", TrackType.AUDIO, TrackColor.SLATE, 2),
        )
        com.studioone.mobile.core.model.ProjectTemplate.LIVE_RECORDING -> listOf(
            Track(TrackId(IdGenerator.newId()), "Stereo Mix", TrackType.AUDIO, TrackColor.TEAL, 0),
        )
        com.studioone.mobile.core.model.ProjectTemplate.REMIX -> listOf(
            Track(TrackId(IdGenerator.newId()), "Stem 1", TrackType.AUDIO, TrackColor.CORAL, 0),
            drumTrack("Drums"),
        )
        com.studioone.mobile.core.model.ProjectTemplate.VOCAL_SESSION -> listOf(
            Track(TrackId(IdGenerator.newId()), "Vocal", TrackType.AUDIO, TrackColor.ROSE, 0,
                input = com.studioone.mobile.core.model.InputRouting(armed = true),
                inserts = listOf(podcastVoiceChain())),
            Track(TrackId(IdGenerator.newId()), "Backing Track", TrackType.AUDIO, TrackColor.SLATE, 1),
        )
        com.studioone.mobile.core.model.ProjectTemplate.EMPTY -> emptyList()
    }

    private fun drumTrack(name: String) = Track(
        TrackId(IdGenerator.newId()), name, TrackType.INSTRUMENT, TrackColor.CORAL, 0,
        instrument = com.studioone.mobile.core.model.InstrumentInstance(
            instrument = com.studioone.mobile.core.model.InstrumentId.DRUM_MACHINE,
            presetId = com.studioone.mobile.core.model.PresetId("sp16-init"),
        ),
    )

    private fun bassTrack(name: String) = Track(
        TrackId(IdGenerator.newId()), name, TrackType.INSTRUMENT, TrackColor.AMBER, 1,
        instrument = com.studioone.mobile.core.model.InstrumentInstance(
            instrument = com.studioone.mobile.core.model.InstrumentId.BASS_SYNTH,
            presetId = com.studioone.mobile.core.model.PresetId("lowend-init"),
        ),
    )

    private fun podcastVoiceChain() = com.studioone.mobile.core.model.FxSlot(
        id = com.studioone.mobile.core.model.FxSlotId(IdGenerator.newId()),
        plugin = com.studioone.mobile.core.model.FxPluginId.HIGH_PASS,
        params = mapOf(0 to 90f),
    )

    fun newProject(name: String, template: com.studioone.mobile.core.model.ProjectTemplate, bpm: Double, ownerId: com.studioone.mobile.core.model.UserId?, timeSignature: TimeSignature = TimeSignature()): Project {
        val now = Clock.System.now()
        return Project(
            id = ProjectId(IdGenerator.newId()),
            ownerId = ownerId,
            name = name,
            template = template,
            tempoMap = com.studioone.mobile.core.model.TempoMap(listOf(com.studioone.mobile.core.model.TempoMarker(0.0, bpm))),
            timeSignature = timeSignature,
            tracks = templateTracks(template),
            createdAt = now,
            updatedAt = now,
        )
    }
}
