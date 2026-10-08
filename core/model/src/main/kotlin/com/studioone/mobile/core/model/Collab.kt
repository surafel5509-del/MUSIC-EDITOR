package com.studioone.mobile.core.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Wire format for collaborative operations. The CRDT layer (core:realtime)
 * guarantees eventual convergence: every mutation of a shared project is
 * expressed as an [OpEnvelope] and broadcast over WebSocket/Supabase Realtime.
 * See docs/COLLABORATION.md for the full protocol & conflict-resolution rules.
 */
@Serializable
data class OpEnvelope(
    val opId: String,                    // UUIDv7 — dedupe key
    val projectId: ProjectId,
    val authorId: UserId,
    val lamport: Long,                   // hybrid logical clock counter
    val wallClock: Instant,
    val payload: CollabOp,
)

/**
 * CRDT operation payload. Each variant targets a replicated data structure:
 *  - field updates use LWW-Register keyed (trackId, field)
 *  - track/clip collections use OR-Set (add-wins with tombstones)
 *  - note lists & automation points use RGA-lite (id-ordered insert/remove)
 */
@Serializable
sealed class CollabOp {
    @Serializable data class AddTrack(val track: Track) : CollabOp()
    @Serializable data class RemoveTrack(val trackId: TrackId) : CollabOp()
    @Serializable data class UpdateTrackField(val trackId: TrackId, val field: TrackField, val value: JsonValueLike) : CollabOp()
    @Serializable data class AddClip(val trackId: TrackId, val clip: Clip) : CollabOp()
    @Serializable data class RemoveClip(val trackId: TrackId, val clipId: ClipId) : CollabOp()
    @Serializable data class UpdateClip(val trackId: TrackId, val clip: Clip) : CollabOp()
    @Serializable data class AddNote(val clipId: ClipId, val note: MidiNote) : CollabOp()
    @Serializable data class RemoveNote(val clipId: ClipId, val noteId: Long) : CollabOp()
    @Serializable data class UpdateNote(val clipId: ClipId, val note: MidiNote) : CollabOp()
    @Serializable data class AddAutomationPoint(val trackId: TrackId, val parameter: AutomatableParameter, val point: AutomationPoint) : CollabOp()
    @Serializable data class RemoveAutomationPoint(val trackId: TrackId, val parameter: AutomatableParameter, val pointId: Long) : CollabOp()
    @Serializable data class UpdateFxSlot(val trackId: TrackId, val slot: FxSlot) : CollabOp()
    @Serializable data class UpdateProjectField(val field: ProjectField, val value: JsonValueLike) : CollabOp()
    @Serializable data class AddComment(val comment: Comment) : CollabOp()
    @Serializable data class ResolveComment(val commentId: CommentId, val resolved: Boolean) : CollabOp()
    /** Rename intent broadcast so remote peers can show "X is renaming…". */
    @Serializable data class Presence(val presence: CollaboratorPresence) : CollabOp()
}

@Serializable
enum class TrackField { NAME, VOLUME, PAN, MUTE, SOLO, ARM, COLOR, ORDER, INPUT, OUTPUT, SENDS, INSERTS, INSTRUMENT, FROZEN }
@Serializable
enum class ProjectField { NAME, TEMPO, TIME_SIGNATURE, KEY, ARTWORK, GENRE, DURATION }

/**
 * Type-erased JSON value carried by field-update ops. We avoid
 * kotlinx JsonElement in the CRDT core to keep the module Android-free and
 * serialization-stable across versions; conversion happens at the edges.
 */
@Serializable
sealed class JsonValueLike {
    @Serializable data class Str(val v: String) : JsonValueLike()
    @Serializable data class Num(val v: Double) : JsonValueLike()
    @Serializable data class Bool(val v: Boolean) : JsonValueLike()
    @Serializable data class Obj(val v: String) : JsonValueLike() // pre-serialized JSON object
    @Serializable data object Null : JsonValueLike()
}

/** Time-anchored comment (marker on the timeline or region selection). */
@Serializable
data class Comment(
    val id: CommentId,
    val projectId: ProjectId,
    val authorId: UserId,
    val authorName: String,
    val text: String,
    val anchorFrame: Long? = null,       // timeline position, if anchored
    val anchorTrackId: TrackId? = null,
    val selectionStartFrame: Long? = null,
    val selectionEndFrame: Long? = null,
    val replies: List<Comment> = emptyList(),
    val resolved: Boolean = false,
    val createdAt: Instant,
)

@Serializable
data class Annotation(
    val id: String,
    val projectId: ProjectId,
    val kind: AnnotationKind,
    val startFrame: Long,
    val endFrame: Long,
    val trackId: TrackId?,
    val label: String,
    val color: TrackColor = TrackColor.AMBER,
    val authorId: UserId,
)

@Serializable
enum class AnnotationKind { MARKER, PUNCH_REGION, LOOP_REGION, CHORD_LABEL, SECTION /* verse/chorus */ }

/** Share link with scoped permission (like a "magic link" for a project). */
@Serializable
data class ShareLink(
    val token: String,
    val projectId: ProjectId,
    val role: ProjectRole,
    val expiresAt: Instant? = null,
    val maxUses: Int? = null,
    val useCount: Int = 0,
    val createdBy: UserId,
    /** When true, recipient gets an independent copy (fork) instead of co-editing. */
    val forkOnAccept: Boolean = false,
)

/** Snapshot in version history. Stored as a compressed project document. */
@Serializable
data class VersionSnapshot(
    val id: VersionId,
    val projectId: ProjectId,
    val label: String,
    val documentVersion: Long,
    val createdBy: UserId,
    val createdAt: Instant,
    val sizeBytes: Long,
    val isAutosave: Boolean = false,
    val storagePath: String,             // backend object-storage key
)

/** Result of a 3-way merge when offline edits conflict (docs/COLLABORATION.md §5). */
@Serializable
data class MergeOutcome(
    val mergedVersion: Long,
    val conflictsResolvedAutomatically: Int,
    val conflictsNeedingReview: List<MergeConflict>,
)

@Serializable
data class MergeConflict(
    val entityKind: String,              // "track" | "clip" | "note" | "project"
    val entityId: String,
    val field: String,
    val localValue: JsonValueLike,
    val remoteValue: JsonValueLike,
    val resolution: ConflictResolution? = null,
)

@Serializable
enum class ConflictResolution { KEEP_LOCAL, KEEP_REMOTE, KEEP_BOTH, MANUAL }
