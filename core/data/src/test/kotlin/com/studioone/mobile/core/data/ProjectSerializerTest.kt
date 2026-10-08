package com.studioone.mobile.core.data

import com.google.common.truth.Truth.assertThat
import com.studioone.mobile.core.data.mapper.ProjectSerializer
import com.studioone.mobile.core.model.AudioClip
import com.studioone.mobile.core.model.AudioFileRef
import com.studioone.mobile.core.model.AutomatableParameter
import com.studioone.mobile.core.model.AutomationCurve
import com.studioone.mobile.core.model.AutomationLane
import com.studioone.mobile.core.model.AutomationPoint
import com.studioone.mobile.core.model.ClipId
import com.studioone.mobile.core.model.Fade
import com.studioone.mobile.core.model.FadeCurve
import com.studioone.mobile.core.model.FxPluginId
import com.studioone.mobile.core.model.FxSlot
import com.studioone.mobile.core.model.FxSlotId
import com.studioone.mobile.core.model.InputRouting
import com.studioone.mobile.core.model.MidiClip
import com.studioone.mobile.core.model.MidiNote
import com.studioone.mobile.core.model.MusicalKey
import com.studioone.mobile.core.model.Project
import com.studioone.mobile.core.model.ProjectId
import com.studioone.mobile.core.model.ProjectTemplate
import com.studioone.mobile.core.model.SampleId
import com.studioone.mobile.core.model.ScaleType
import com.studioone.mobile.core.model.Send
import com.studioone.mobile.core.model.BusId
import com.studioone.mobile.core.model.TempoMap
import com.studioone.mobile.core.model.TempoMarker
import com.studioone.mobile.core.model.TimeSignature
import com.studioone.mobile.core.model.Track
import com.studioone.mobile.core.model.TrackColor
import com.studioone.mobile.core.model.TrackId
import com.studioone.mobile.core.model.TrackType
import com.studioone.mobile.core.model.UserId
import kotlinx.datetime.Clock
import org.junit.Test

/**
 * The document format IS the sync contract: any change to serialized shape
 * must keep old documents readable (kotlinx ignoreUnknownKeys + explicit
 * defaults). This test locks the round-trip for a fully-loaded project.
 */
class ProjectSerializerTest {

    private fun fullProject(): Project {
        val now = Clock.System.now()
        return Project(
            id = ProjectId("proj-1"),
            ownerId = UserId("user-1"),
            name = "Full Session",
            template = ProjectTemplate.SONG,
            tempoMap = TempoMap(listOf(TempoMarker(0.0, 92.5), TempoMarker(8.0, 96.0))),
            timeSignature = TimeSignature(6, 8),
            key = MusicalKey(9, ScaleType.MINOR_PENTATONIC),
            sampleRate = 96_000,
            tracks = listOf(
                Track(
                    id = TrackId("t-audio"), name = "Lead Vocal", type = TrackType.AUDIO,
                    color = TrackColor.ROSE, orderIndex = 0,
                    volumeDb = -3.5f, pan = -0.2f, mute = false, solo = true,
                    input = InputRouting(inputChannel = 1, armed = true, inputGainDb = 12f),
                    inserts = listOf(
                        FxSlot(FxSlotId("fx-1"), FxPluginId.COMPRESSOR, mapOf(0 to -20f, 1 to 4f)),
                        FxSlot(FxSlotId("fx-2"), FxPluginId.PARAMETRIC_EQ, mapOf(2 to 3200f), enabled = false),
                    ),
                    sends = listOf(Send(BusId("return_reverb_1"), levelDb = -12f, preFader = true)),
                    automation = listOf(
                        AutomationLane(
                            parameter = AutomatableParameter.TRACK_VOLUME,
                            points = listOf(
                                AutomationPoint(0, -3.5f, AutomationCurve.LINEAR),
                                AutomationPoint(1920, 0f, AutomationCurve.S_CURVE),
                            ),
                        ),
                    ),
                    clips = listOf(
                        AudioClip(
                            id = ClipId("c-1"), startFrame = 0, lengthFrames = 192_000,
                            name = "Take 3", gainDb = 1.5f,
                            fade = Fade(240, 960, FadeCurve.EQUAL_POWER),
                            fileRef = AudioFileRef(SampleId("s-1"), "content://takes/t3.wav", durationFrames = 192_000),
                            sourceOffsetFrames = 4800, reverse = true, pitchShiftSemitones = -2.5f,
                        ),
                    ),
                ),
                Track(
                    id = TrackId("t-midi"), name = "Keys", type = TrackType.INSTRUMENT,
                    clips = listOf(
                        MidiClip(
                            id = ClipId("c-2"), startFrame = 0, lengthFrames = 96_000,
                            notes = listOf(
                                MidiNote(1, 0, 480, 57, 100, 0),
                                MidiNote(2, 480, 240, 60, 88, 0),
                            ),
                        ),
                    ),
                ),
            ),
            createdAt = now, updatedAt = now, documentVersion = 42,
        )
    }

    @Test
    fun `document roundtrip preserves every field`() {
        val original = fullProject()
        val json = ProjectSerializer.encode(original)
        val decoded = ProjectSerializer.decode(json)
        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `entity mapping roundtrips metadata columns`() {
        val original = fullProject()
        val entity = ProjectSerializer.toEntity(original, dirty = true)
        assertThat(entity.bpm).isEqualTo(92.5)
        assertThat(entity.trackCount).isEqualTo(2)
        assertThat(entity.syncStatus).isEqualTo("LOCAL_ONLY")
        assertThat(entity.dirty).isTrue()
        val restored = ProjectSerializer.toModel(entity)
        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun `unknown fields in older-newer documents are ignored`() {
        val json = ProjectSerializer.encode(fullProject())
        // Simulate a document written by a FUTURE app version with extra fields.
        val withExtra = json.replaceFirst("{", "{\"futureFeatureFlag\": true, \"unknownObj\": {\"a\": 1},")
        val decoded = ProjectSerializer.decode(withExtra)
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.name).isEqualTo("Full Session")
    }

    @Test
    fun `missing optional fields fall back to defaults`() {
        // A minimal document from an older version (no sends/automation).
        val minimal = """
            {"id":"p","ownerId":null,"name":"Old","template":"EMPTY",
             "tempoMap":{"markers":[{"bar":0.0,"bpm":120.0,"curve":"STEP"}]},
             "timeSignature":{"numerator":4,"beatUnit":4},
             "key":{"tonicPc":0,"scale":"MAJOR"},
             "sampleRate":48000,"bitDepth":"FLOAT_32","tracks":[],"buses":[],
             "durationBars":64.0,"isArchived":false,"isFavorite":false,
             "syncStatus":"LOCAL_ONLY","documentVersion":0,
             "createdAt":"2024-01-01T00:00:00Z","updatedAt":"2024-01-01T00:00:00Z"}
        """.trimIndent()
        val decoded = ProjectSerializer.decode(minimal)
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.tracks).isEmpty()
        assertThat(decoded.collaborators).isEmpty()
    }

    @Test
    fun `polymorphic clip discriminator survives roundtrip`() {
        val json = ProjectSerializer.encode(fullProject())
        assertThat(json).contains("\"kind\"") // classDiscriminator present for sealed Clip
        val decoded = ProjectSerializer.decode(json)!!
        val clips = decoded.tracks.first().clips
        assertThat(clips.first()).isInstanceOf(AudioClip::class.java)
    }
}
