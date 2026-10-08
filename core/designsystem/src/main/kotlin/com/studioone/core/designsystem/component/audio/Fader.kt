package com.studioone.core.designsystem.component.audio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.studioone.core.common.util.AudioMath

/**
 * Vertical channel fader: 0..4x gain mapped to dB markings. Drag anywhere on
 * the track to jump + fine drag. Value semantics: linear gain.
 */
@Composable
fun Fader(
    gain: Float,
    onGainChange: (Float) -> Unit,
    onGestureEnd: () -> Unit = {},
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String = "Volume fader",
) {
    var heightPx by remember { mutableFloatStateOf(1f) }

    // Colors hoisted outside the draw lambda (draw scope cannot read composition locals).
    val grooveColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val tickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val thumbColor = MaterialTheme.colorScheme.primary
    val thumbDisabledColor = MaterialTheme.colorScheme.outline

    Canvas(
        modifier = modifier
            .width(44.dp)
            .fillMaxHeight()
            .semantics { contentDescription = label }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                heightPx = size.height.toFloat()
                detectDragGestures(
                    onDragEnd = onGestureEnd,
                    onDrag = { change, _ ->
                        change.consume()
                        val y = change.position.y.coerceIn(0f, heightPx)
                        onGainChange(positionToGain(y / heightPx))
                    },
                    onDragCancel = onGestureEnd,
                )
            },
    ) {
        heightPx = size.height
        val trackWidth = size.width * 0.08f
        val centerX = size.width / 2
        val fraction = gainToPosition(gain)
        val thumbY = size.height * (1f - fraction)

        // Track groove.
        drawRoundRect(
            color = grooveColor,
            topLeft = Offset(centerX - trackWidth / 2, 0f),
            size = Size(trackWidth, size.height),
            cornerRadius = CornerRadius(trackWidth / 2),
        )
        // Unity-gain tick (0 dB ~= 0.8 gain in our 0..4 mapping).
        val unityY = size.height * (1f - gainToPosition(1f))
        drawLine(
            color = tickColor,
            start = Offset(centerX - size.width * 0.3f, unityY),
            end = Offset(centerX + size.width * 0.3f, unityY),
            strokeWidth = 2.dp.toPx(),
        )
        // Thumb.
        val thumbHeight = size.width * 0.5f
        drawRoundRect(
            color = if (enabled) thumbColor else thumbDisabledColor,
            topLeft = Offset(size.width * 0.12f, thumbY - thumbHeight / 2),
            size = Size(size.width * 0.76f, thumbHeight),
            cornerRadius = CornerRadius(6.dp.toPx()),
        )
    }
}

/** Maps gain 0..4 to fader position 0..1 (perceptual curve). */
fun gainToPosition(gain: Float): Float {
    val db = AudioMath.linearToDb(gain)
    return ((db + 60f) / 72f).coerceIn(0f, 1f) // -60dB..+12dB travel
}

fun positionToGain(position: Float): Float {
    val db = position.coerceIn(0f, 1f) * 72f - 60f
    return AudioMath.dbToLinear(db)
}
