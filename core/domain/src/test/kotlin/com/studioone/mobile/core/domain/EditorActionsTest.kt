package com.studioone.mobile.core.domain

import com.google.common.truth.Truth.assertThat
import com.studioone.mobile.core.common.DataResult
import com.studioone.mobile.core.model.AudioClip
import com.studioone.mobile.core.model.AudioFileRef
import com.studioone.mobile.core.model.ClipId
import com.studioone.mobile.core.model.MidiClip
import com.studioone.mobile.core.model.MidiNote
import com.studioone.mobile.core.model.ProjectId
import com.studioone.mobile.core.model.ProjectTemplate
import com.studioone.mobile.core.model.SampleId
import com.studioone.mobile.core.model.Track
import com.studioone.mobile.core.model.TrackId
import com.studioone.mobile.core.model.TrackType
import org.junit.Test

class EditorActionsTest {

    private val project = EditorActions.newProject("Test", ProjectTemplate.EMPTY, 120.0, null)

    private fun audioTrack(): Track = Track(TrackId("t1"), "Audio", TrackType.AUDIO)

    private fun clip(id: String = "c1", start: Long = 0, length: Long = 48_000) = AudioClip(
        id = ClipId(id), startFrame = start, lengthFrames = length,
        fileRef = AudioFileRef(SampleId("s1"), "file:///x.wav", durationFrames = length),
    )

    @Test
    fun `addTrack appends with increasing order`() {
        var p = project
        p = EditorActions.addTrack(p, TrackType.AUDIO)
        p = EditorActions.addTrack(p, TrackType.INSTRUMENT)
        assertThat(p.tracks).hasSize(2)
        assertThat(p.tracks[1].type).isEqualTo(TrackType.INSTRUMENT)
        assertThat(p.tracks[1].orderIndex).isEqualTo(1)
    }

    @Test
    fun `removeTrack reindexes remaining tracks`() {
        var p = project
        p = EditorActions.addTrack(p)
        p = EditorActions.addTrack(p)
        val first = p.tracks[0].id
        p = EditorActions.removeTrack(p, first)
        assertThat(p.tracks).hasSize(1)
        assertThat(p.tracks[0].orderIndex).isEqualTo(0)
    }

    @Test
    fun `addClip rejects wrong clip type for track`() {
        val p = EditorActions.addTrack(project, TrackType.AUDIO)
        val midi = MidiClip(ClipId("m1"), 0, 48_000)
        val result = EditorActions.addClip(p, p.tracks[0].id, midi)
        assertThat(result).isInstanceOf(DataResult.Failure::class.java)
    }

    @Test
    fun `splitClip cuts audio clip into two with adjusted source offset`() {
        var p = EditorActions.addTrack(project, TrackType.AUDIO)
        val tid = p.tracks[0].id
        p = (EditorActions.addClip(p, tid, clip(length = 96_000)) as DataResult.Success).data
        val result = EditorActions.splitClip(p, tid, ClipId("c1"), atFrame = 48_000)
        assertThat(result).isInstanceOf(DataResult.Success::class.java)
        val clips = (result as DataResult.Success).data.track(tid)!!.clips
        assertThat(clips).hasSize(2)
        val right = clips[1] as AudioClip
        assertThat(right.startFrame).isEqualTo(48_000)
        assertThat(right.sourceOffsetFrames).isEqualTo(48_000)
        assertThat(right.lengthFrames).isEqualTo(48_000)
    }

    @Test
    fun `splitClip at edge is rejected`() {
        var p = EditorActions.addTrack(project, TrackType.AUDIO)
        val tid = p.tracks[0].id
        p = (EditorActions.addClip(p, tid, clip()) as DataResult.Success).data
        assertThat(EditorActions.splitClip(p, tid, ClipId("c1"), 0)).isInstanceOf(DataResult.Failure::class.java)
        assertThat(EditorActions.splitClip(p, tid, ClipId("c1"), 48_000)).isInstanceOf(DataResult.Failure::class.java)
    }

    @Test
    fun `split midi clip redistributes notes across halves`() {
        var p = EditorActions.addTrack(project, TrackType.MIDI)
        val tid = p.tracks[0].id
        val midi = MidiClip(
            id = ClipId("m1"), startFrame = 0, lengthFrames = 96_000,
            notes = listOf(
                MidiNote(1, startTick = 0, durationTicks = 240, key = 60),
                MidiNote(2, startTick = 480, durationTicks = 240, key = 64), // second beat
                MidiNote(3, startTick = 300, durationTicks = 300, key = 67), // straddles split
            ),
        )
        p = (EditorActions.addClip(p, tid, midi) as DataResult.Success).data
        val grid = GridMath(p.tempoMap, p.timeSignature, p.sampleRate)
        val splitFrame = grid.ticksToFrames(480) // start of beat 2
        val result = EditorActions.splitClip(p, tid, ClipId("m1"), splitFrame)
        val clips = (result as DataResult.Success).data.track(tid)!!.clips.filterIsInstance<MidiClip>()
        assertThat(clips).hasSize(2)
        val left = clips[0]; val right = clips[1]
        assertThat(left.notes.map { it.id }).containsExactly(1L, 3L)
        assertThat(left.notes.first { it.id == 3L }.durationTicks).isEqualTo(180L) // truncated at split
        assertThat(right.notes.map { it.id }).containsExactly(2L, 3L)
        assertThat(right.notes.first { it.id == 3L }.startTick).isEqualTo(0L) // rebased into right clip
    }

    @Test
    fun `moveClip with ripple shifts later clips`() {
        var p = EditorActions.addTrack(project, TrackType.AUDIO)
        val tid = p.tracks[0].id
        p = (EditorActions.addClip(p, tid, clip("c1", 0, 48_000)) as DataResult.Success).data
        p = (EditorActions.addClip(p, tid, clip("c2", 48_000, 48_000)) as DataResult.Success).data
        p = EditorActions.moveClip(p, tid, ClipId("c1"), 24_000, ripple = true)
        val clips = p.track(tid)!!.clips
        assertThat(clips[0].startFrame).isEqualTo(24_000)
        assertThat(clips[1].startFrame).isEqualTo(72_000) // shifted by the same delta
    }

    @Test
    fun `rippleDelete closes the gap`() {
        var p = EditorActions.addTrack(project, TrackType.AUDIO)
        val tid = p.tracks[0].id
        p = (EditorActions.addClip(p, tid, clip("c1", 0, 48_000)) as DataResult.Success).data
        p = (EditorActions.addClip(p, tid, clip("c2", 48_000, 48_000)) as DataResult.Success).data
        p = EditorActions.rippleDelete(p, tid, ClipId("c1"))
        assertThat(p.track(tid)!!.clips.single().startFrame).isEqualTo(0)
    }

    @Test
    fun `template tracks match template intent`() {
        assertThat(EditorActions.templateTracks(ProjectTemplate.PODCAST)).hasSize(3)
        assertThat(EditorActions.templateTracks(ProjectTemplate.PODCAST)[0].inserts).isNotEmpty()
        assertThat(EditorActions.templateTracks(ProjectTemplate.BEAT).first().instrument).isNotNull()
        assertThat(EditorActions.templateTracks(ProjectTemplate.EMPTY)).isEmpty()
    }

    @Test
    fun `frozen track rejects new clips`() {
        var p = EditorActions.addTrack(project, TrackType.AUDIO)
        val tid = p.tracks[0].id
        p = EditorActions.updateTrack(p, tid) { it.copy(frozen = true) }
        assertThat(EditorActions.addClip(p, tid, clip())).isInstanceOf(DataResult.Failure::class.java)
    }
}
