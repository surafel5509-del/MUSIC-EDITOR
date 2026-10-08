package com.studioone.mobile.core.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.studioone.mobile.core.designsystem.theme.S1Colors
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * Stereo LED-style level meter with peak-hold and clip latch.
 * Levels are linear amplitude; the scale is dB-mapped (-48..+3) so the
 * gradient matches human loudness perception. Peak holds 1.2s then decays.
 */
@Composable
fun LevelMeter(
    peakL: Float,
    peakR: Float,
    modifier: Modifier = Modifier,
    clipL: Boolean = false,
    clipR: Boolean = false,
    orientation: MeterOrientation = MeterOrientation.Vertical,
) {
    var holdL by remember { mutableFloatStateOf(0f) }
    var holdR by remember { mutableFloatStateOf(0f) }
    var holdAge by remember { mutableFloatStateOf(0) }

    LaunchedEffect(peakL, peakR) {
        holdL = max(holdL * 0.995f, peakL)
        holdR = max(holdR * 0.995f, peakR)
        if (peakL >= holdL && peakR >= holdR) holdAge = 0
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(80)
            holdAge++
            if (holdAge > 15) { // ~1.2s hold, then decay
                holdL *= 0.92f
                holdR *= 0.92f
            }
        }
    }

    Canvas(modifier = modifier.width(if (orientation == MeterOrientation.Vertical) 14.dp else 120.dp).fillMaxHeight()) {
        val segCount = 24
        val segGap = size.height * 0.008f
        val segH = (size.height - segGap * (segCount - 1)) / segCount
        val halfW = (size.width - 3.dp.toPx()) / 2

        for (i in 0 until segCount) {
            val segNorm = (i + 1) / segCount.toFloat()
            val segDb = -48f + 51f * segNorm
            val color = when {
                segDb > 0f -> S1Colors.MeterRed
                segDb > -6f -> S1Colors.MeterYellow
                else -> S1Colors.MeterGreen
            }
            val y = size.height - (i + 1) * (segH + segGap)
            // Left
            val linThreshold = linearForNorm(segNorm)
            val lOn = peakL >= linThreshold
            drawRoundRect(
                color = if (lOn) color else color.copy(alpha = 0.12f),
                topLeft = Offset(0f, y),
                size = Size(halfW, segH),
                cornerRadius = CornerRadius(1.5.dp.toPx()),
            )
            // Right
            val rOn = peakR >= linThreshold
            drawRoundRect(
                color = if (rOn) color else color.copy(alpha = 0.12f),
                topLeft = Offset(halfW + 3.dp.toPx(), y),
                size = Size(halfW, segH),
                cornerRadius = CornerRadius(1.5.dp.toPx()),
            )
            // Peak-hold caps.
            if (holdL >= linThreshold && holdL < linearForNorm(segNorm + 1f / segCount)) {
                drawRoundRect(color = Color.White, topLeft = Offset(0f, y), size = Size(halfW, segH * 0.5f), cornerRadius = CornerRadius(1.dp.toPx()))
            }
            if (holdR >= linThreshold && holdR < linearForNorm(segNorm + 1f / segCount)) {
                drawRoundRect(color = Color.White, topLeft = Offset(halfW + 3.dp.toPx(), y), size = Size(halfW, segH * 0.5f), cornerRadius = CornerRadius(1.dp.toPx()))
            }
        }
        // Clip latches.
        if (clipL) drawRoundRect(color = S1Colors.MeterRed, topLeft = Offset(0f, 0f), size = Size(halfW, segH * 0.8f), cornerRadius = CornerRadius(2.dp.toPx()))
        if (clipR) drawRoundRect(color = S1Colors.MeterRed, topLeft = Offset(halfW + 3.dp.toPx(), 0f), size = Size(halfW, segH * 0.8f), cornerRadius = CornerRadius(2.dp.toPx()))
    }
}

enum class MeterOrientation { Vertical, Horizontal }

/** Meter segment position -> linear amplitude (inverse of the dB mapping). */
private fun linearForNorm(norm: Float): Float {
    val db = -48f + 51f * norm.coerceIn(0f, 1f)
    return kotlin.math.pow(10f, db / 20f)
}
