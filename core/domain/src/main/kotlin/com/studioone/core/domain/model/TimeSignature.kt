package com.studioone.core.domain.model

import kotlinx.serialization.Serializable

/** Musical time signature, e.g. 4/4, 3/4, 7/8. */
@Serializable
data class TimeSignature(
    val numerator: Int = 4,
    val denominator: Int = 4,
) {
    init {
        require(numerator in 1..64) { "numerator out of range" }
        require(denominator in setOf(2, 4, 8, 16)) { "denominator must be 2, 4, 8 or 16" }
    }

    /** Length of one bar expressed in quarter-note beats. */
    val beatsPerBar: Double
        get() = numerator * (4.0 / denominator)
}
