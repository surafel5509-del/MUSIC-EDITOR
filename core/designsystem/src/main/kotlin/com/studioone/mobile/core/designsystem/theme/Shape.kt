package com.studioone.mobile.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Corner language: tight radii for pro-tool density, pills for transport. */
object S1Shapes {
    val extraSmall = RoundedCornerShape(4.dp)
    val small = RoundedCornerShape(8.dp)
    val medium = RoundedCornerShape(12.dp)
    val large = RoundedCornerShape(16.dp)
    val clip = RoundedCornerShape(6.dp)     // timeline clips
    val pad = RoundedCornerShape(10.dp)     // drum pads
    val pill = RoundedCornerShape(50)
}
