package com.studioone.mobile.core.realtime.crdt

import com.studioone.mobile.core.model.JsonValueLike

/**
 * LWW-Element-Register: value + the HLC stamp of its last write.
 * Concurrent writes resolve by stamp order (millis -> counter -> nodeId), so
 * all replicas converge without communication.
 */
data class LwwRegister<T>(
    val value: T,
    val stamp: HlcTimestamp,
) {
    fun merge(other: LwwRegister<T>): LwwRegister<T> =
        if (other.stamp > stamp) other else this

    fun write(newValue: T, at: HlcTimestamp): LwwRegister<T> =
        if (at >= stamp) LwwRegister(newValue, at) else this
}

/**
 * OR-Set (observed-remove set) over string element ids.
 *
 * add-wins semantics: an element is present when it has at least one add-tag
 * that hasn't been observed by a remove. Tags are HLC-encoded op ids. This
 * makes concurrent add+remove resolve to "present" — the right default for
 * creative tools (losing a collaborator's freshly added clip to a stale
 * remove is far worse than keeping a clip someone deleted offline).
 */
class OrSet<T>(
    private val elements: MutableMap<String, MutableMap<String, T>> = mutableMapOf(),
    // elementId -> (tag -> value)
) {
    fun add(elementId: String, value: T, tag: String) {
        elements.getOrPut(elementId) { mutableMapOf() }[tag] = value
    }

    /** Remove all *observed* tags for the element (causal remove). */
    fun remove(elementId: String, observedTags: Set<String>) {
        val tags = elements[elementId] ?: return
        observedTags.forEach { tags.remove(it) }
        if (tags.isEmpty()) elements.remove(elementId)
    }

    fun remove(elementId: String) {
        elements.remove(elementId)
    }

    fun get(elementId: String): T? = elements[elementId]?.values?.firstOrNull()

    fun contains(elementId: String): Boolean =
        elements[elementId]?.isNotEmpty() == true

    fun values(): Map<String, T> = elements.mapValues { (_, tags) ->
        // Deterministic pick when duplicate tags hold different values:
        // max tag (latest HLC) wins.
        tags.maxByOrNull { (tag, _) -> tag }!!.value
    }

    fun tagsOf(elementId: String): Set<String> = elements[elementId]?.keys ?: emptySet()

    fun merge(other: OrSet<T>): OrSet<T> {
        val result = OrSet<T>()
        val ids = elements.keys + other.elements.keys
        for (id in ids) {
            val mine = elements[id] ?: emptyMap()
            val theirs = other.elements[id] ?: emptyMap()
            val union = mine + theirs
            if (union.isNotEmpty()) result.elements[id] = union.toMutableMap()
        }
        return result
    }
}

/**
 * Field-level LWW map used for entity attributes (track volume, project name,
 * clip positions…). Each field carries its own stamp so two collaborators
 * editing DIFFERENT fields of the same track never conflict.
 */
class LwwFieldMap(
    private val fields: MutableMap<String, LwwRegister<JsonValueLike>> = mutableMapOf(),
) {
    fun write(field: String, value: JsonValueLike, at: HlcTimestamp) {
        val existing = fields[field]
        fields[field] = existing?.write(value, at) ?: LwwRegister(value, at)
    }

    fun read(field: String): JsonValueLike? = fields[field]?.value

    fun stampOf(field: String): HlcTimestamp? = fields[field]?.stamp

    fun merge(other: LwwFieldMap): LwwFieldMap {
        val result = LwwFieldMap()
        for ((k, v) in fields) result.fields[k] = v
        for ((k, v) in other.fields) {
            result.fields[k] = result.fields[k]?.merge(v) ?: v
        }
        return result
    }

    fun toMap(): Map<String, JsonValueLike> = fields.mapValues { it.value.value }
}
