package com.studioone.core.network.collab

import com.studioone.core.domain.model.collab.CollabOp
import java.util.concurrent.atomic.AtomicLong

/**
 * Conflict-free merge engine for collaborative editing.
 *
 * Strategy (see docs/COLLABORATION.md):
 *  - AddTrack/AddClip/RemoveClip form an add-wins OR-set keyed by entity id:
 *    an entity exists iff (adds - removes) is non-empty; concurrent add/remove
 *    resolves in favor of the op with the higher (lamport, siteId) pair.
 *  - Field updates (UpdateTrackField/UpdateClipField) are LWW registers:
 *    the write with the highest (lamport, siteId) wins.
 *  - Lamport clocks are advanced on every send/receive so causality is
 *    preserved across sites without synchronized wall clocks.
 */
class CrdtEngine(private val siteId: String) {

    private val clock = AtomicLong(0)

    fun tick(): Long = clock.incrementAndGet()

    /** Advance clock on receive so we stay ahead of everything observed. */
    fun observe(lamport: Long) {
        clock.updateAndGet { maxOf(it, lamport) }
    }

    /** Orders two ops deterministically across sites. Returns >0 when [a] wins. */
    fun wins(a: CollabOp, b: CollabOp): Int {
        val byTime = a.lamport.compareTo(b.lamport)
        return if (byTime != 0) byTime else a.siteId.compareTo(b.siteId)
    }

    /**
     * Reduces an op log into per-entity field maps (LWW) and existence sets
     * (add-wins). Pure function: same log in, same state out, order-independent.
     */
    fun reduce(ops: List<CollabOp>): ReducedState {
        val trackFields = HashMap<String, HashMap<String, FieldValue>>()
        val clipFields = HashMap<String, HashMap<String, FieldValue>>()
        val trackExists = HashMap<String, EntityLifecycle>()
        val clipExists = HashMap<String, EntityLifecycle>()
        val chat = ArrayList<CollabOp.ChatMessage>()

        for (op in ops.sortedWith(compareBy({ it.lamport }, { it.siteId }))) {
            when (op) {
                is CollabOp.AddTrack -> trackExists.merge(
                    op.opId, EntityLifecycle(op), ::mergeLifecycle,
                ).also {
                    trackFields.getOrPut(op.opId) { HashMap() }["__doc"] =
                        FieldValue(op.trackJson, op.lamport, op.siteId)
                    trackExists["track:${op.trackJson.hashCode()}"] = trackExists[op.opId]!!
                }

                is CollabOp.RemoveTrack -> trackExists["track-id:${op.trackId}"] =
                    EntityLifecycle(op, removed = true)

                is CollabOp.AddClip -> clipExists.merge(
                    op.opId, EntityLifecycle(op), ::mergeLifecycle,
                ).also {
                    clipFields.getOrPut(op.opId) { HashMap() }["__doc"] =
                        FieldValue(op.clipJson, op.lamport, op.siteId)
                }

                is CollabOp.RemoveClip -> clipExists["clip-id:${op.clipId}"] =
                    EntityLifecycle(op, removed = true)

                is CollabOp.UpdateTrackField -> {
                    val fields = trackFields.getOrPut(op.trackId) { HashMap() }
                    val current = fields[op.field]
                    if (current == null || current.lamport < op.lamport ||
                        (current.lamport == op.lamport && op.siteId > current.siteId)
                    ) {
                        fields[op.field] = FieldValue(op.valueJson, op.lamport, op.siteId)
                    }
                }

                is CollabOp.UpdateClipField -> {
                    val fields = clipFields.getOrPut(op.clipId) { HashMap() }
                    val current = fields[op.field]
                    if (current == null || current.lamport < op.lamport ||
                        (current.lamport == op.lamport && op.siteId > current.siteId)
                    ) {
                        fields[op.field] = FieldValue(op.valueJson, op.lamport, op.siteId)
                    }
                }

                is CollabOp.ChatMessage -> chat += op
            }
        }

        return ReducedState(
            trackFields = trackFields,
            clipFields = clipFields,
            trackLifecycle = trackExists,
            clipLifecycle = clipExists,
            chat = chat.sortedBy { it.sentAt },
        )
    }

    private fun mergeLifecycle(a: EntityLifecycle, b: EntityLifecycle): EntityLifecycle =
        if (b.eventLamport > a.eventLamport ||
            (b.eventLamport == a.eventLamport && b.siteId > a.siteId)
        ) b else a

    data class FieldValue(val json: String, val lamport: Long, val siteId: String)

    data class EntityLifecycle(
        val opId: String,
        val eventLamport: Long,
        val siteId: String,
        val removed: Boolean,
    ) {
        constructor(op: CollabOp, removed: Boolean = false) : this(op.opId, op.lamport, op.siteId, removed)
    }

    data class ReducedState(
        val trackFields: Map<String, Map<String, FieldValue>>,
        val clipFields: Map<String, Map<String, FieldValue>>,
        val trackLifecycle: Map<String, EntityLifecycle>,
        val clipLifecycle: Map<String, EntityLifecycle>,
        val chat: List<CollabOp.ChatMessage>,
    ) {
        /** An entity survives if its latest lifecycle event is not a remove. */
        fun trackAlive(key: String): Boolean = trackLifecycle[key]?.removed != true
        fun clipAlive(key: String): Boolean = clipLifecycle[key]?.removed != true
    }
}
