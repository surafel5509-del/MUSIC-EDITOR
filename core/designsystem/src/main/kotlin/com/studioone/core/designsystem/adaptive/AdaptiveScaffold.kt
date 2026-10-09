package com.studioone.core.designsystem.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Device class buckets the workspace adapts to. */
enum class DeviceClass { COMPACT, MEDIUM, EXPANDED }

/**
 * Window-size-class helper shared by all feature screens. Phone portrait is
 * COMPACT, landscape/foldable inner display MEDIUM, tablets EXPANDED.
 *
 * (Consumes androidx.window metrics via LocalWindowInfo at call sites; kept
 * as a pure function so it is unit-testable.)
 */
object AdaptiveLayout {

    fun deviceClass(widthDp: Dp, heightDp: Dp): DeviceClass = when {
        widthDp >= 840.dp -> DeviceClass.EXPANDED
        widthDp >= 600.dp -> DeviceClass.MEDIUM
        else -> DeviceClass.COMPACT
    }

    /** Timeline track row height adapts to device class + touch setting. */
    fun trackRowHeight(deviceClass: DeviceClass, largeTargets: Boolean): Dp = when (deviceClass) {
        DeviceClass.COMPACT -> if (largeTargets) 72.dp else 60.dp
        DeviceClass.MEDIUM -> if (largeTargets) 84.dp else 72.dp
        DeviceClass.EXPANDED -> if (largeTargets) 96.dp else 84.dp
    }

    /** Whether the mixer renders beside the timeline (expanded) or as a tab. */
    fun showSidePanels(deviceClass: DeviceClass): Boolean = deviceClass == DeviceClass.EXPANDED
}
