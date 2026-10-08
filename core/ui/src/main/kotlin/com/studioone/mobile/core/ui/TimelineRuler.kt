package com.studioone.mobile.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.studioone.mobile.core.designsystem.LocalS1Dimensions
import com.studioone.mobile.core.model.GridLabels
import androidx.compose.ui.graphics.nativeCanvas

/**
 * Timeline ruler: adaptive bar/beat grid with labels.
 *
 * Zoom-aware tick selection (the "1-2-5" rule of thumb used by every DAW):
 * picks the coarsest division whose pixel spacing stays >= 56dp so labels
 * never collide, then draws subdivisions at 1/4 of that.
 */
@Composable
fun TimelineRuler(
    pixelsPerFrame: Float,
    scrollOffsetFrames: Long,
    gridLabels: GridLabels,
    modifier: Modifier = Modifier,
    loopStartFrame: Long? = null,
    loopEndFrame: Long? = null,
    markers: List<Pair<Long, String>> = emptyList(),
) {
    val height = LocalS1Dimensions.current.timelineRulerHeight.dp
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val tickColor = MaterialTheme.colorScheme.outline
    val loopColor = MaterialTheme.colorScheme.primary

    Canvas(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
        val framesPerPx = 1f / pixelsPerFrame
        val minLabelPx = 56.dp.toPx()

        // Choose the division (in frames) for major ticks.
        val candidate = gridLabels.divisionsFrames
        val major = candidate.firstOrNull { it * pixelsPerFrame >= minLabelPx }
            ?: candidate.last()
        val minor = major / 4

        val firstVisible = scrollOffsetFrames
        val lastVisible = scrollOffsetFrames + (size.width * framesPerPx).toLong()

        val startMajor = (firstVisible / major) * major
        var frame = startMajor
        while (frame <= lastVisible) {
            val x = (frame - firstVisible) * pixelsPerFrame
            val isBar = gridLabels.isBarStart(frame)
            drawLine(
                color = if (isBar) tickColor else tickColor.copy(alpha = 0.5f),
                start = Offset(x, size.height * (if (isBar) 0.35f else 0.65f)),
                end = Offset(x, size.height),
                strokeWidth = if (isBar) 2f else 1f,
            )
            if (isBar) {
                val label = gridLabels.labelFor(frame)
                drawContext.canvas.nativeCanvas.drawText(
                    label, x + 4f, size.height * 0.35f,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(255,
                            (labelColor.red * 255).toInt(), (labelColor.green * 255).toInt(),
                            (labelColor.blue * 255).toInt())
                        textSize = 11.dp.toPx()
                        isAntiAlias = true
                    },
                )
            }
            // Minor ticks.
            if (minor > 0) {
                for (m in 1 until 4) {
                    val mx = x + m * minor * pixelsPerFrame
                    if (mx in 0f..size.width) {
                        drawLine(
                            color = tickColor.copy(alpha = 0.3f),
                            start = Offset(mx, size.height * 0.8f),
                            end = Offset(mx, size.height),
                            strokeWidth = 1f,
                        )
                    }
                }
            }
            frame += major
        }

        // Loop region.
        if (loopStartFrame != null && loopEndFrame != null) {
            val lx = (loopStartFrame - firstVisible) * pixelsPerFrame
            val rx = (loopEndFrame - firstVisible) * pixelsPerFrame
            drawRect(color = loopColor.copy(alpha = 0.18f),
                topLeft = Offset(lx, 0f), size = androidx.compose.ui.geometry.Size(rx - lx, size.height))
            drawLine(loopColor, Offset(lx, 0f), Offset(lx, size.height), 3f)
            drawLine(loopColor, Offset(rx, 0f), Offset(rx, size.height), 3f)
        }

        // Named markers.
        markers.forEach { (markerFrame, text) ->
            val x = (markerFrame - firstVisible) * pixelsPerFrame
            if (x in 0f..size.width) {
                drawLine(MaterialTheme.colorScheme.tertiary, Offset(x, 0f), Offset(x, size.height * 0.5f), 3f)
            }
        }
    }
}
