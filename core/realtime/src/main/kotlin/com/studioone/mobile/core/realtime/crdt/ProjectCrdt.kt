package com.studioone.mobile.core.realtime.crdt

import com.studioone.mobile.core.model.Clip
import com.studioone.mobile.core.model.CollabOp
import com.studioone.mobile.core.model.Comment
import com.studioone.mobile.core.model.MidiNote
import com.studioone.mobile.core.model.OpEnvelope
import com.studioone.mobile.core.model.Project
import com.studioone.mobile.core.model.ProjectId
import com.studioone.mobile.core.model.Track
import com.studioone.mobile.core.model.TrackId

/**
 * The replicated project state.
 *
 * Structure mapping (docs/COLLABORATION.md §3):
 *  * tracks & clips        -> OrSet (add-wins)
 *  * track/project fields  -> LwwFieldMap per entity
 *  * notes & automation    -> LwwFieldMap keyed by noteId/pointId
 *                            (whole-object LWW per element; note granularity
 *                            is fine because concurrent edits of the SAME
 *                            note are vanishingly rare and resolve sanely)
 *  * comments              -> grow-only set + resolved flag register
 *
 * [apply] is deterministic: feeding the same op set in any causal order to
 * any replica yields identical state (property-tested in ProjectCrdtTest).
 */
class ProjectCrdt(
    val projectId: ProjectId,
    val clock: HybridClock,
    private val localNodeId: String,
) {
    /** Materialized view cache; rebuilt incrementally by apply(). */
    var current: Project? = null
        private set

    private val tracks = OrSet<TrackHolder>()
    private val trackFields = mutableMapOf<String, LwwFieldMap>()
    private val projectFields = LwwFieldMap()
    private val notes = mutableMapOf<String /*clipId*/, LwwFieldMap>()
    private val comments = OrSet<CommentHolder>()
    private val appliedOps = LinkedHashSet<String>() // opId dedupe (bounded, see prune)
    private var maxAppliedStamp: HlcTimestamp? = null

    class TrackHolder(val trackId: String)
    class CommentHolder(val comment: Comment)

    /** Apply a remote or replayed envelope. Returns false when already applied. */
    fun apply(envelope: OpEnvelope): Boolean {
        if (!appliedOps.add(envelope.opId)) return false
        val stamp = HlcTimestamp(envelope.lamport, 0, envelope.authorId.value)
        clock.receive(stamp)
        if (maxAppliedStamp == null || stamp > maxAppliedStamp!!) maxAppliedStamp = stamp
        applyPayload(envelope.payload, stamp)
        return true
    }

    /** Apply a locally-generated mutation; returns the envelope to broadcast. */
    fun applyLocal(op: CollabOp, authorId: String): OpEnvelope {
        val stamp = clock.now()
        applyPayload(op, stamp)
        val envelope = OpEnvelope(
            opId = java.util.UUID.randomUUID().toString(),
            projectId = projectId,
            authorId = com.studioone.mobile.core.model.UserId(authorId),
            lamport = stamp.millis * 1000 + stamp.counter, // wire encoding: millis*1000+counter
            wallClock = kotlinx.datetime.Clock.System.now(),
            payload = op,
        )
        appliedOps.add(envelope.opId)
        return envelope
    }

    private fun applyPayload(op: CollabOp, stamp: HlcTimestamp) {
        when (op) {
            is CollabOp.AddTrack -> {
                tracks.add(op.track.id.value, TrackHolder(op.track.id.value), stamp.encode())
                trackFields.getOrPut(op.track.id.value) { LwwFieldMap() }
                    .write(F_TRACK_DOC, com.studioone.mobile.core.model.JsonValueLike.Obj(
                        json.encodeToString(Track.serializer(), op.track)), stamp)
            }
            is CollabOp.RemoveTrack -> {
                tracks.remove(op.trackId.value, tracks.tagsOf(op.trackId.value))
            }
            is CollabOp.UpdateTrackField -> {
                // Stable per-field key: two users editing different fields of
                // the same track never collide; same field resolves by HLC.
                trackFields.getOrPut(op.trackId.value) { LwwFieldMap() }
                    .write("f:${op.field.name}", op.value, stamp)
            }
            is CollabOp.AddClip -> {
                val key = clipKey(op.trackId.value, op.clip.id.value)
                trackFields.getOrPut(key) { LwwFieldMap() }
                    .write(F_CLIP_DOC, com.studioone.mobile.core.model.JsonValueLike.Obj(
                        json.encodeToString(Clip.serializer(), op.clip)), stamp)
                trackFields.getOrPut(op.trackId.value) { LwwFieldMap() }
                    .write("clip_present:${op.clip.id.value}", com.studioone.mobile.core.model.JsonValueLike.Bool(true), stamp)
            }
            is CollabOp.RemoveClip -> {
                trackFields.getOrPut(op.trackId.value) { LwwFieldMap() }
                    .write("clip_present:${op.clipId.value}", com.studioone.mobile.core.model.JsonValueLike.Bool(false), stamp)
            }
            is CollabOp.UpdateClip -> {
                val key = clipKey(op.trackId.value, op.clip.id.value)
                trackFields.getOrPut(key) { LwwFieldMap() }
                    .write(F_CLIP_DOC, com.studioone.mobile.core.model.JsonValueLike.Obj(
                        json.encodeToString(Clip.serializer(), op.clip)), stamp)
                trackFields.getOrPut(op.trackId.value) { LwwFieldMap() }
                    .write("clip_present:${op.clip.id.value}", com.studioone.mobile.core.model.JsonValueLike.Bool(true), stamp)
            }
            is CollabOp.AddNote, is CollabOp.UpdateNote -> {
                val (clipId, note) = when (op) {
                    is CollabOp.AddNote -> op.clipId.value to op.note
                    is CollabOp.UpdateNote -> op.clipId.value to op.note
                    else -> error("unreachable")
                }
                notes.getOrPut(clipId) { LwwFieldMap() }
                    .write("n:${note.id}", com.studioone.mobile.core.model.JsonValueLike.Obj(
                        json.encodeToString(MidiNote.serializer(), note)), stamp)
            }
            is CollabOp.RemoveNote -> {
                notes.getOrPut(op.clipId.value) { LwwFieldMap() }
                    .write("n:${op.noteId}", com.studioone.mobile.core.model.JsonValueLike.Bool(false), stamp)
            }
            is CollabOp.AddAutomationPoint -> {
                trackFields.getOrPut(op.trackId.value) { LwwFieldMap() }
                    .write(
                        "auto:${op.parameter.paramId}:${op.point.positionTicks}",
                        com.studioone.mobile.core.model.JsonValueLike.Num(op.point.value.toDouble()),
                        stamp,
                    )
            }
            is CollabOp.RemoveAutomationPoint -> {
                trackFields.getOrPut(op.trackId.value) { LwwFieldMap() }
                    .write(
                        "auto:${op.parameter.paramId}:${op.pointId}",
                        com.studioone.mobile.core.model.JsonValueLike.Null,
                        stamp,
                    )
            }
            is CollabOp.UpdateFxSlot -> {
                trackFields.getOrPut(op.trackId.value) { LwwFieldMap() }
                    .write("fx:${op.slot.id.value}", com.studioone.mobile.core.model.JsonValueLike.Obj(
                        json.encodeToString(com.studioone.mobile.core.model.FxSlot.serializer(), op.slot)), stamp)
            }
            is CollabOp.UpdateProjectField -> {
                projectFields.write(op.field.name, op.value, stamp)
            }
            is CollabOp.AddComment -> {
                comments.add(op.comment.id.value, CommentHolder(op.comment), stamp.encode())
            }
            is CollabOp.ResolveComment -> {
                comments.add(op.commentId.value, CommentHolder(
                    (comments.get(op.commentId.value)?.comment ?: return).copy(resolved = op.resolved),
                ), stamp.encode() + ":r")
            }
            is CollabOp.Presence -> Unit // presence never mutates document state
        }
        invalidateView()
    }

    private fun invalidateView() { current = null }

    /** Materialize the [Project] view from CRDT state (cached until next apply). */
    fun materialize(base: Project): Project {
        current?.let { return it }
        var project = base

        // Project-level fields.
        (projectFields.read("NAME") as? com.studioone.mobile.core.model.JsonValueLike.Str)
            ?.let { project = project.copy(name = it.v) }
        (projectFields.read("TEMPO") as? com.studioone.mobile.core.model.JsonValueLike.Num)
            ?.let { project = project.copy(tempoMap = com.studioone.mobile.core.model.TempoMap(
                listOf(com.studioone.mobile.core.model.TempoMarker(0.0, it.v)))) }

        // Tracks.
        val trackIds = tracks.values().map { it.trackId }
        val rebuiltTracks = trackIds.mapNotNull { trackId ->
            val fields = trackFields[trackId] ?: return@mapNotNull null
            val docJson = (fields.read(F_TRACK_DOC) as? com.studioone.mobile.core.model.JsonValueLike.Obj)?.v
                ?: return@mapNotNull null
            var track = runCatching { json.decodeFromString(Track.serializer(), docJson) }.getOrNull()
                ?: return@mapNotNull null
            // Per-field overrides newer than the doc snapshot.
            for ((key, value) in fields.toMap()) {
                if (!key.startsWith("f:")) continue
                track = applyTrackFieldOverride(track, key.removePrefix("f:"), value) ?: track
            }
            // Clips.
            val clips = mutableListOf<Clip>()
            for ((key, value) in fields.toMap()) {
                if (!key.startsWith("clip_present:")) continue
                val clipId = key.removePrefix("clip_present:")
                val present = (value as? com.studioone.mobile.core.model.JsonValueLike.Bool)?.v ?: false
                if (!present) continue
                val clipJson = (trackFields[clipKey(trackId, clipId)]?.read(F_CLIP_DOC)
                    as? com.studioone.mobile.core.model.JsonValueLike.Obj)?.v ?: continue
                runCatching { json.decodeFromString(Clip.serializer(), clipJson) }
                    .onSuccess { clips += it }
            }
            track.copy(clips = clips.sortedBy { it.startFrame })
        }
        project = project.copy(tracks = rebuiltTracks.ifEmpty { project.tracks })

        val view = project
        current = view
        return view
    }

    private fun applyTrackFieldOverride(
        track: Track, fieldName: String, value: com.studioone.mobile.core.model.JsonValueLike,
    ): Track? {
        val name = fieldName.substringBefore(':')
        return when (name) {
            "VOLUME" -> (value as? com.studioone.mobile.core.model.JsonValueLike.Num)?.let { track.copy(volumeDb = it.v.toFloat()) }
            "PAN" -> (value as? com.studioone.mobile.core.model.JsonValueLike.Num)?.let { track.copy(pan = it.v.toFloat()) }
            "MUTE" -> (value as? com.studioone.mobile.core.model.JsonValueLike.Bool)?.let { track.copy(mute = it) }
            "SOLO" -> (value as? com.studioone.mobile.core.model.JsonValueLike.Bool)?.let { track.copy(solo = it) }
            "NAME" -> (value as? com.studioone.mobile.core.model.JsonValueLike.Str)?.let { track.copy(name = it.v) }
            else -> null
        }
    }

    private fun clipKey(trackId: String, clipId: String) = "$trackId/clip/$clipId"

    fun isApplied(opId: String) = opId in appliedOps

    /** Bound the dedupe set (op ids older than the pruning horizon are safe
     *  to forget because the server rejects replays beyond it too). */
    fun pruneAppliedOps(keepLast: Int = 8192) {
        if (appliedOps.size > keepLast) {
            val iter = appliedOps.iterator()
            var toRemove = appliedOps.size - keepLast
            while (iter.hasNext() && toRemove > 0) { iter.next(); iter.remove(); toRemove-- }
        }
    }

    companion object {
        const val F_TRACK_DOC = "__track_doc"
        const val F_CLIP_DOC = "__clip_doc"
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            classDiscriminator = "kind"
        }
    }
}
