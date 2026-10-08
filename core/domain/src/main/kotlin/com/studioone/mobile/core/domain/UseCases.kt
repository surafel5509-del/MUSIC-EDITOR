package com.studioone.mobile.core.domain

import com.studioone.mobile.core.common.DataError
import com.studioone.mobile.core.common.DataResult
import com.studioone.mobile.core.common.IdGenerator
import com.studioone.mobile.core.model.Entitlement
import com.studioone.mobile.core.model.ExportFormat
import com.studioone.mobile.core.model.ExportKind
import com.studioone.mobile.core.model.ExportSettings
import com.studioone.mobile.core.model.Project
import com.studioone.mobile.core.model.ProjectId
import com.studioone.mobile.core.model.ProjectTemplate
import com.studioone.mobile.core.model.Track
import com.studioone.mobile.core.model.TrackId
import com.studioone.mobile.core.model.TrackType
import javax.inject.Inject

/**
 * Use cases encapsulate cross-repository workflows and business rules.
 * One responsibility each; injected into ViewModels. Naming: verb-first.
 */

class CreateProject @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val entitlementRepository: EntitlementRepository,
) {
    suspend operator fun invoke(
        name: String,
        template: ProjectTemplate,
        bpm: Double = 120.0,
    ): DataResult<Project> {
        val limit = entitlementRepository.checkLimit(LimitKind.PROJECT_COUNT)
        if (limit is DataResult.Failure) return limit
        return projectRepository.createProject(name.ifBlank { "Untitled ${template.name.lowercase()}" }, template, bpm)
    }
}

class AddTrackWithEntitlement @Inject constructor(
    private val trackRepository: TrackRepository,
    private val entitlementRepository: EntitlementRepository,
) {
    suspend operator fun invoke(projectId: ProjectId, type: TrackType, name: String? = null): DataResult<Project> {
        val limit = entitlementRepository.checkLimit(LimitKind.TRACK_COUNT)
        if (limit is DataResult.Failure) return limit
        val track = Track(
            id = TrackId(IdGenerator.newId()),
            name = name ?: type.name.lowercase().replaceFirstChar { it.uppercase() },
            type = type,
        )
        return trackRepository.addTrack(projectId, track)
    }
}

/**
 * Single funnel for "is this action allowed on the current plan" checks with
 * user-facing messaging. Features call this before gated actions; the paywall
 * sheet renders [DataError] messages from here.
 */
class CheckEntitlement @Inject constructor(
    private val entitlementRepository: EntitlementRepository,
) {
    suspend operator fun invoke(kind: LimitKind): DataResult<Unit> =
        entitlementRepository.checkLimit(kind)

    suspend fun canExport(settings: ExportSettings): DataResult<Unit> {
        if (settings.kind == ExportKind.STEMS) {
            val stem = entitlementRepository.checkLimit(LimitKind.STEM_EXPORT)
            if (stem is DataResult.Failure) return stem
        }
        if (settings.format == ExportFormat.FLAC || settings.format == ExportFormat.OGG) {
            val lossless = entitlementRepository.checkLimit(LimitKind.LOSSLESS_EXPORT)
            if (lossless is DataResult.Failure) return lossless
        }
        if (!settings.vbr && settings.bitrateKbps > 192) {
            val bitrate = entitlementRepository.checkLimit(LimitKind.HIGH_BITRATE)
            if (bitrate is DataResult.Failure) return bitrate
        }
        return DataResult.Success(Unit)
    }
}

/**
 * Duplicate + fork differ: duplicate stays private to the owner; fork is the
 * remix flow (copies media references under fair-use of the original's
 * license, records lineage via Post.isRemixOf when published).
 */
class ForkOrDuplicateProject @Inject constructor(
    private val projectRepository: ProjectRepository,
) {
    suspend fun duplicate(projectId: ProjectId, newName: String): DataResult<Project> =
        projectRepository.duplicateProject(projectId, newName)

    suspend fun remix(projectId: ProjectId): DataResult<Project> =
        projectRepository.forkProject(projectId)
}
