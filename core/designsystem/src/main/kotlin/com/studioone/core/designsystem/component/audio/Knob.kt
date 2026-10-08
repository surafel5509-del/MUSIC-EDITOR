package com.studioone.core.designsystem.component.audio

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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rotary knob with vertical-drag control (industry standard: drag up = value
 * up). Supports logarithmic scaling for frequency parameters.
 */
@Composable
fun Knob(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    label: String = "",
    logarithmic: Boolean = false,
    enabled: Boolean = true,
) {
    val startAngle = 135f
    val sweep = 270f
    val normalized = remember(value, range, logarithmic) {
        if (logarithmic) {
            val minLog = kotlin.math.ln(range.start.toDouble())
            val maxLog = kotlin.math.ln(range.endInclusive.toDouble())
            ((kotlin.math.ln(value.toDouble()) - minLog) / (maxLog - minLog)).toFloat()
        } else {
            (value - range.start) / (range.endInclusive - range.start)
        }
    }.coerceIn(0f, 1f)

    var dragAccumulator by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current

    // Colors hoisted outside the draw lambda (draw scope cannot read composition locals).
    val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val valueColor = MaterialTheme.colorScheme.primary
    val indicatorColor = MaterialTheme.colorScheme.onSurface
    val disabledColor = MaterialTheme.colorScheme.outline

    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = label }
            .pointerInput(enabled, range) {
                if (!enabled) return@pointerInput
                detectVerticalDragGestures(
                    onDragStart = { dragAccumulator = 0f },
                    onVerticalDrag = { change, drag ->
                        change.consume()
                        dragAccumulator -= drag / with(density) { 1.dp.toPx() } * 0.008f
                        val delta = dragAccumulator
                        dragAccumulator = 0f
                        val newNormalized = (normalized + delta).coerceIn(0f, 1f)
                        val newValue = if (logarithmic) {
                            val minLog = kotlin.math.ln(range.start.toDouble())
                            val maxLog = kotlin.math.ln(range.endInclusive.toDouble())
                            kotlin.math.exp(minLog + (maxLog - minLog) * newNormalized).toFloat()
                        } else {
                            range.start + (range.endInclusive - range.start) * newNormalized
                        }
                        onValueChange(newValue)
                    },
                )
            },
    ) {
        val stroke = this.size.minDimension * 0.09f
        val radius = this.size.minDimension / 2 - stroke
        val center = Offset(this.size.width / 2, this.size.height / 2)

        // Track arc.
        drawArc(
            color = trackColor,
            startAngle = startAngle,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        // Value arc.
        if (normalized > 0.001f) {
            drawArc(
                color = valueColor,
                startAngle = startAngle,
                sweepAngle = sweep * normalized,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        // Indicator line.
        val angle = Math.toRadians((startAngle + sweep * normalized).toDouble())
        val inner = radius * 0.35
        drawLine(
            color = if (enabled) indicatorColor else disabledColor,
            start = Offset(center.x + inner * cos(angle).toFloat(), center.y + inner * sin(angle).toFloat()),
            end = Offset(center.x + radius * 0.85f * cos(angle).toFloat(), center.y + radius * 0.85f * sin(angle).toFloat()),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}
