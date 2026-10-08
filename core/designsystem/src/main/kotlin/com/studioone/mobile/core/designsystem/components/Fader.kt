package com.studioone.mobile.core.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.studioone.mobile.core.designsystem.LocalS1Dimensions
import kotlin.math.pow

/**
 * Channel fader. Value is dB (-inf encoded as [minDb]); the track is drawn on
 * an audio taper so the useful -40..0 region occupies most of the throw.
 * Touch target respects the large-targets accessibility setting.
 */
@Composable
fun Fader(
    db: Float,
    onDbChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    minDb: Float = -60f,
    maxDb: Float = 12f,
    enabled: Boolean = true,
    label: String = "Volume fader",
) {
    val width = LocalS1Dimensions.current.faderWidth.dp
    Canvas(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .semantics {
                contentDescription = label
                stateDescription = if (db <= minDb) "muted" else "%.1f dB".format(db)
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    val height = size.height.toFloat()
                    val pixelDelta = -dragAmount / height // up = increase
                    val rangeDb = maxDb - minDb
                    // Audio taper: equal pixel distance = equal perceptual step.
                    val currentNorm = dbToNorm(db, minDb, maxDb)
                    val newNorm = (currentNorm + pixelDelta).coerceIn(0f, 1f)
                    onDbChange(normToDb(newNorm, minDb, maxDb))
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val trackW = w * 0.14f
        val trackX = (w - trackW) / 2
        val norm = dbToNorm(db, minDb, maxDb)
        val capY = h * (1f - norm)

        // Track groove.
        drawRoundRect(
            color = MaterialTheme.colorScheme.surfaceVariant,
            topLeft = Offset(trackX, h * 0.04f),
            size = Size(trackW, h * 0.92f),
            cornerRadius = CornerRadius(trackW / 2),
        )
        // Filled portion (unity at 0dB marker).
        val unityNorm = dbToNorm(0f, minDb, maxDb)
        val unityY = h * (1f - unityNorm)
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                startY = capY, endY = unityY,
            ),
            topLeft = Offset(trackX, capY),
            size = Size(trackW, (unityY - capY).coerceAtLeast(0f)),
            cornerRadius = CornerRadius(trackW / 2),
        )
        // Unity (0 dB) tick.
        drawLine(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            start = Offset(trackX - w * 0.1f, unityY),
            end = Offset(trackX + trackW + w * 0.1f, unityY),
            strokeWidth = 2f,
        )
        // Cap.
        val capH = h * 0.07f
        val capW = w * 0.72f
        drawRoundRect(
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
            topLeft = Offset((w - capW) / 2, capY - capH / 2),
            size = Size(capW, capH),
            cornerRadius = CornerRadius(4.dp.toPx()),
        )
        drawLine(
            color = MaterialTheme.colorScheme.surface,
            start = Offset((w - capW) / 2 + 4f, capY),
            end = Offset((w + capW) / 2 - 4f, capY),
            strokeWidth = 2f,
        )
    }
}

/** Perceptual fader law: piecewise — fine resolution around unity. */
fun dbToNorm(db: Float, minDb: Float, maxDb: Float): Float {
    if (db <= minDb) return 0f
    val t = ((db - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
    return t.pow(0.62f) // compresses the top (loud) region less than the bottom
}

fun normToDb(norm: Float, minDb: Float, maxDb: Float): Float {
    val t = norm.coerceIn(0f, 1f)
    return minDb + (maxDb - minDb) * t.pow(1f / 0.62f)
}
