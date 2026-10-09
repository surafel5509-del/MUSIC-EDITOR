package com.studioone.core.domain.editor

import com.studioone.core.domain.model.AudioClip
import com.studioone.core.domain.model.Clip
import com.studioone.core.domain.model.GridSettings
import com.studioone.core.domain.model.MidiClip
import com.studioone.core.domain.model.Project
import com.studioone.core.domain.model.Track
import com.studioone.core.domain.model.TrackId

/** Immutable snapshot of the open project, consumed by the arrange UI. */
data class EditorState(
    val project: Project,
    val tracks: List<Track> = emptyList(),
    val clips: List<Clip> = emptyList(),
    val grid: GridSettings = GridSettings(),
    val selection: Set<String> = emptySet(),
    val playheadFrame: Long = 0,
    val isPlaying: Boolean = false,
    val isRecording: Boolean = false,
    val loopEnabled: Boolean = false,
    val loopStartFrame: Long = 0,
    val loopEndFrame: Long = 0,
) {
    fun clipsOn(trackId: TrackId): List<Clip> = clips.filter { it.trackId == trackId }
    fun audioClips(): List<AudioClip> = clips.filterIsInstance<AudioClip>()
    fun midiClips(): List<MidiClip> = clips.filterIsInstance<MidiClip>()
    fun track(trackId: TrackId): Track? = tracks.firstOrNull { it.id == trackId }
}
