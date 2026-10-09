package com.studioone.core.designsystem.component.audio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.studioone.core.common.util.AudioMath

/**
 * Stereo LED-style level meter. Values are linear amplitudes; drawing uses
 * dB segmentation with green/amber/red zones.
 */
@Composable
fun LevelMeter(
    levelLeft: Float,
    levelRight: Float,
    modifier: Modifier = Modifier,
    peakLeft: Float = levelLeft,
    peakRight: Float = levelRight,
    clipLeft: Boolean = false,
    clipRight: Boolean = false,
) {
    // Colors hoisted outside the draw lambda (draw scope cannot read composition locals).
    val unlitColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    val peakColor = MaterialTheme.colorScheme.onSurface

    Canvas(
        modifier = modifier
            .width(14.dp)
            .fillMaxHeight(),
    ) {
        val laneWidth = size.width / 2f - 1.dp.toPx()

        fun drawLane(level: Float, peak: Float, clip: Boolean, x: Float) {
            val db = AudioMath.linearToDb(level)
            val fraction = ((db + 60f) / 66f).coerceIn(0f, 1f)
            val barHeight = size.height * fraction
            val segments = 24
            val segmentHeight = size.height / segments

            for (i in 0 until segments) {
                val segFraction = (i + 1f) / segments
                val segY = size.height - (i + 1) * segmentHeight
                val lit = segFraction <= fraction
                val color = when {
                    !lit -> unlitColor
                    segFraction > 0.92f -> Color(0xFFE5484D)
                    segFraction > 0.78f -> Color(0xFFFFC53D)
                    else -> Color(0xFF46A758)
                }
                drawRect(
                    color = color,
                    topLeft = Offset(x, segY),
                    size = Size(laneWidth, segmentHeight - 1.dp.toPx()),
                )
            }
            // Peak hold indicator.
            val peakDb = AudioMath.linearToDb(peak)
            val peakFraction = ((peakDb + 60f) / 66f).coerceIn(0f, 1f)
            val peakY = size.height * (1f - peakFraction)
            drawRect(
                color = if (clip) Color(0xFFE5484D) else peakColor,
                topLeft = Offset(x, peakY - 1.dp.toPx()),
                size = Size(laneWidth, 2.dp.toPx()),
            )
        }

        drawLane(levelLeft, peakLeft, clipLeft, 0f)
        drawLane(levelRight, peakRight, clipRight, size.width / 2f + 1.dp.toPx())
    }
}
