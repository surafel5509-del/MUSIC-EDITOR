package com.studioone.mobile.core.model

import kotlinx.serialization.Serializable

/**
 * Parameters that can be automated. Each maps to a native engine parameter id
 * (see core:audio ParameterIds) — the ordinals are part of the persisted
 * protocol, so NEVER reorder; only append.
 */
@Serializable
enum class AutomatableParameter(val paramId: Int, val displayName: String) {
    TRACK_VOLUME(1000, "Volume"),
    TRACK_PAN(1001, "Pan"),
    TRACK_WIDTH(1002, "Width"),
    TRACK_MUTE(1003, "Mute"),
    TRACK_SEND_1(1010, "Send 1"),
    TRACK_SEND_2(1011, "Send 2"),
    TRACK_SEND_3(1012, "Send 3"),
    TRACK_SEND_4(1013, "Send 4"),
    FX_PARAM_BASE(2000, "FX Param"),   // fxSlotIndex * 64 + paramIndex added at runtime
    INSTRUMENT_MACRO_BASE(6000, "Macro"),
    TEMPO(9000, "Tempo"),
    MASTER_VOLUME(9100, "Master Volume"),
    PITCH(9200, "Pitch");

    companion object {
        /** Parameter id for slot [slot] param [param] within an insert chain. */
        fun fxParam(slot: Int, param: Int): Int = FX_PARAM_BASE.paramId + slot * 64 + param
    }
}

@Serializable
enum class AutomationCurve { LINEAR, EXPONENTIAL, LOGARITHMIC, S_CURVE, HOLD }

/**
 * One automation breakpoint. [positionTicks] is musical time (PPQ) so
 * automation follows tempo changes; the native engine receives pre-baked
 * per-buffer parameter samples computed in the Kotlin graph compiler.
 */
@Serializable
data class AutomationPoint(
    val positionTicks: Long,
    val value: Float,
    val curve: AutomationCurve = AutomationCurve.LINEAR,
)

@Serializable
data class AutomationLane(
    val parameter: AutomatableParameter,
    val points: List<AutomationPoint> = emptyList(),
    val enabled: Boolean = true,
    val mode: AutomationMode = AutomationMode.TOUCH,
) {
    /** Interpolated value at [tick]. HOLD curve returns last point's value. */
    fun valueAt(tick: Long): Float? {
        if (points.isEmpty()) return null
        if (tick <= points.first().positionTicks) return points.first().value
        val next = points.firstOrNull { it.positionTicks > tick } ?: return points.last().value
        val prev = points[points.indexOf(next) - 1]
        val span = (next.positionTicks - prev.positionTicks).coerceAtLeast(1)
        val t = ((tick - prev.positionTicks).toFloat() / span).coerceIn(0f, 1f)
        return when (prev.curve) {
            AutomationCurve.LINEAR -> prev.value + (next.value - prev.value) * t
            AutomationCurve.HOLD -> prev.value
            AutomationCurve.EXPONENTIAL -> {
                val v0 = prev.value.coerceAtLeast(1e-4f); val v1 = next.value.coerceAtLeast(1e-4f)
                v0 * Math.pow((v1 / v0).toDouble(), t.toDouble()).toFloat()
            }
            AutomationCurve.LOGARITHMIC -> {
                val v0 = prev.value.coerceAtLeast(1e-4f); val v1 = next.value.coerceAtLeast(1e-4f)
                v0 * Math.pow((v1 / v0).toDouble(), (t * t).toDouble()).toFloat()
            }
            AutomationCurve.S_CURVE -> {
                val s = t * t * (3 - 2 * t) // smoothstep
                prev.value + (next.value - prev.value) * s
            }
        }
    }
}

@Serializable
enum class AutomationMode {
    OFF,     // lane ignored
    READ,    // playback follows lane
    TOUCH,   // writes while touched, returns to lane on release
    LATCH,   // writes while touched, holds last value after release
    WRITE,   // overwrites entire pass
}
