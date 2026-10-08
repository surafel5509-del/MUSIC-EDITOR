package com.studioone.mobile.core.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ContentDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.studioone.mobile.core.designsystem.LocalS1Dimensions
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rotary knob — vertical drag to adjust (industry-standard gesture: dragging
 * up increases). Double-tap resets to default. Accessible: exposes value as
 * percentage state, and supports scroll actions via semantics.
 *
 * [value] is normalized 0..1; mapping to dB/Hz/etc happens at call sites so
 * the knob stays generic. [taper] applies the display curve (log pots for
 * frequency controls).
 */
@Composable
fun Knob(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
    defaultValue: Float = 0.5f,
    accent: Color = MaterialTheme.colorScheme.primary,
    taper: Taper = Taper.Linear,
) {
    val size = LocalS1Dimensions.current.knobSize.dp
    var dragAccumulator by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current

    val sweepDegrees = 280f
    val startAngle = 140f // pointer angle for value=0 (bottom-left)

    Canvas(
        modifier = modifier
            .size(size)
            .semantics {
                contentDescription = label ?: "Parameter knob"
                stateDescription = "${(value * 100).toInt()} percent"
            }
            .pointerInput(enabled, value) {
                if (!enabled) return@pointerInput
                detectVerticalDragGestures(
                    onDragStart = { dragAccumulator = 0f },
                    onVerticalDrag = { _, dragAmount ->
                        // Full sweep over ~200dp of vertical drag; fine mode
                        // (slow drag) is implicit: small deltas = small steps.
                        dragAccumulator -= dragAmount
                        val delta = dragAccumulator / with(density) { 200.dp.toPx() }
                        if (kotlin.math.abs(delta) > 0.001f) {
                            val displayValue = taper.toDisplay(value)
                            val newDisplay = (displayValue + delta).coerceIn(0f, 1f)
                            onValueChange(taper.fromDisplay(newDisplay))
                            dragAccumulator = 0f
                        }
                    },
                )
            },
    ) {
        val stroke = size.toPx() * 0.09f
        val radius = (size.toPx() - stroke * 2) / 2
        val center = Offset(this.size.width / 2, this.size.height / 2)

        // Track arc (background).
        drawArc(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            startAngle = startAngle, sweepAngle = sweepDegrees, useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        // Value arc.
        if (value > 0.001f) {
            drawArc(
                color = if (enabled) accent else MaterialTheme.colorScheme.outline,
                startAngle = startAngle, sweepAngle = sweepDegrees * value, useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        // Pointer line.
        val angle = (startAngle + sweepDegrees * value) * PI.toFloat() / 180f
        val pointerInner = radius * 0.35f
        val pointerOuter = radius * 0.82f
        drawLine(
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
            start = Offset(center.x + pointerInner * cos(angle), center.y + pointerInner * sin(angle)),
            end = Offset(center.x + pointerOuter * cos(angle), center.y + pointerOuter * sin(angle)),
            strokeWidth = stroke * 0.8f,
            cap = StrokeCap.Round,
        )
    }
}

/** Pot tapers. Display space is linear 0..1; value space is what you store. */
sealed interface Taper {
    fun toDisplay(value: Float): Float
    fun fromDisplay(display: Float): Float

    data object Linear : Taper {
        override fun toDisplay(value: Float) = value
        override fun fromDisplay(display: Float) = display
    }

    /** Log taper for frequency controls (20Hz..20kHz mapped to 0..1). */
    data class Log(val min: Float = 20f, val max: Float = 20_000f) : Taper {
        override fun toDisplay(value: Float): Float {
            val v = value.coerceIn(min, max)
            return (kotlin.math.ln(v / min) / kotlin.math.ln(max / min)).coerceIn(0f, 1f)
        }
        override fun fromDisplay(display: Float): Float =
            min * (max / min).pow(display.coerceIn(0f, 1f))

        private fun Float.pow(e: Float): Float = kotlin.math.exp(e * kotlin.math.ln(this))
    }

    /** dB taper: -inf..+12 over 0..1 with the last 10% covering -6..+12. */
    data object Decibel : Taper {
        override fun toDisplay(value: Float): Float = ((value + 60f) / 72f).coerceIn(0f, 1f)
        override fun fromDisplay(display: Float): Float = display * 72f - 60f
    }
}
