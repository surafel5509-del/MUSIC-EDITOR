package com.studioone.mobile.core.realtime.crdt

/**
 * Hybrid Logical Clock (HLC): (physical millis, logical counter, nodeId).
 *
 * Why HLC over Lamport-only: wall-clock proximity keeps "version history" and
 * "who changed what when" human-meaningful, while the logical component
 * guarantees a total order for concurrent events. nodeId (the user's device
 * session UUID) breaks exact ties deterministically — every replica resolves
 * concurrent writes identically WITHOUT coordination, which is the property
 * that makes offline merge possible.
 */
data class HlcTimestamp(
    val millis: Long,
    val counter: Int,
    val nodeId: String,
) : Comparable<HlcTimestamp> {
    override fun compareTo(other: HlcTimestamp): Int {
        val byMillis = millis.compareTo(other.millis)
        if (byMillis != 0) return byMillis
        val byCounter = counter.compareTo(other.counter)
        if (byCounter != 0) return byCounter
        return nodeId.compareTo(other.nodeId)
    }

    fun encode(): String = "%013d:%05d:%s".format(millis, counter, nodeId)

    companion object {
        fun decode(s: String): HlcTimestamp? {
            val parts = s.split(':', limit = 3)
            if (parts.size != 3) return null
            val millis = parts[0].toLongOrNull() ?: return null
            val counter = parts[1].toIntOrNull() ?: return null
            return HlcTimestamp(millis, counter, parts[2])
        }
    }
}

class HybridClock(private val nodeId: String, private val timeSource: () -> Long = System::currentTimeMillis) {
    private var lastMillis = 0L
    private var counter = 0

    /** Local event: advance and stamp. */
    @Synchronized
    fun now(): HlcTimestamp {
        val physical = timeSource()
        if (physical > lastMillis) {
            lastMillis = physical
            counter = 0
        } else {
            counter++
        }
        return HlcTimestamp(lastMillis, counter, nodeId)
    }

    /** Receive a remote stamp: merge so causality is preserved. */
    @Synchronized
    fun receive(remote: HlcTimestamp): HlcTimestamp {
        val physical = timeSource()
        when {
            physical > lastMillis && physical > remote.millis -> {
                lastMillis = physical; counter = 0
            }
            remote.millis > lastMillis -> {
                lastMillis = remote.millis; counter = remote.counter + 1
            }
            lastMillis > remote.millis -> {
                counter++
            }
            else -> { // equal millis
                counter = maxOf(counter, remote.counter) + 1
            }
        }
        return HlcTimestamp(lastMillis, counter, nodeId)
    }
}
