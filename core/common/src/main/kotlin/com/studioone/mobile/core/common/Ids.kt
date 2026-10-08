package com.studioone.mobile.core.common

import java.security.SecureRandom
import java.util.UUID

/**
 * ID generation. UUIDv7-style: unix-millis prefix makes IDs roughly sorted by
 * creation time, which keeps Postgres/Firestore indexes hot and lets the CRDT
 * layer break ties deterministically (larger id wins concurrent inserts).
 */
object IdGenerator {
    private val random = SecureRandom()

    fun newUuid(): String = UUID.randomUUID().toString()

    /** Time-sortable UUID (v7 layout). */
    fun newId(): String {
        val ts = System.currentTimeMillis()
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        // 48-bit big-endian unix ms timestamp
        for (i in 0..5) bytes[i] = ((ts ushr (8 * (5 - i))) and 0xFF).toByte()
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()   // version 7
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()   // RFC 4122 variant
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20)}"
    }
}
