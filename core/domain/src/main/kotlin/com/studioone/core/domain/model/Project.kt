package com.studioone.core.domain.model

import com.studioone.core.domain.model.music.MusicalKey
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/** Lifecycle of a project document. */
enum class ProjectStatus { ACTIVE, ARCHIVED, DELETED }

/**
 * The top-level document of the DAW. A project owns its tracks, clips, buses,
 * automation, tempo map and collaboration metadata. Everything is persisted
 * locally first (Room) and synced to the backend lazily.
 */
data class Project(
    val id: ProjectId,
    val name: String,
    val description: String = "",
    val templateId: String? = null,
    val tempo: Double = 120.0,
    val timeSignature: TimeSignature = TimeSignature(4, 4),
    val key: MusicalKey? = null,
    val sampleRate: Int = 44_100,
    val status: ProjectStatus = ProjectStatus.ACTIVE,
    val colorIndex: Int = 0,
    val version: Int = 1,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
    val durationFrames: Long = 0L,
    val ownerId: UserId? = null,
    val collabSessionId: String? = null,
) {
    val isCollaborative: Boolean get() = collabSessionId != null
}

/** Beat grid configuration shown in the editor toolbar. */
data class GridSettings(
    val snapEnabled: Boolean = true,
    val snapDivision: SnapDivision = SnapDivision.BEAT_1_4,
    val quantizeOnRecord: Boolean = false,
)

enum class SnapDivision(val beats: Double, val label: String) {
    BEAT_1_1(1.0, "1/1"),
    BEAT_1_2(0.5, "1/2"),
    BEAT_1_4(0.25, "1/4"),
    BEAT_1_8(0.125, "1/8"),
    BEAT_1_16(0.0625, "1/16"),
    BEAT_1_32(0.03125, "1/32"),
    TRIPLET_1_8(1.0 / 6.0, "1/8T"),
    TRIPLET_1_16(1.0 / 12.0, "1/16T"),
    OFF(0.0, "Off"),
}

/** A project template spec: applied when the user creates a new project from it. */
data class ProjectTemplate(
    val id: String,
    val name: String,
    val description: String,
    val category: TemplateCategory,
    val tempo: Double,
    val timeSignature: TimeSignature,
    val key: MusicalKey?,
    val tracks: List<TrackSpec>,
)

enum class TemplateCategory { BEAT, SONG, PODCAST, LIVE_RECORDING, REMIX, BLANK }

/** Declarative track definition inside a template. */
data class TrackSpec(
    val name: String,
    val type: TrackType,
    val colorIndex: Int,
    val instrumentId: String? = null,
    val effectChainIds: List<String> = emptyList(),
    val armed: Boolean = false,
)
