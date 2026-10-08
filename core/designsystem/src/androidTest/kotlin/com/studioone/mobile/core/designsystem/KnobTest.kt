package com.studioone.mobile.core.designsystem

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.studioone.mobile.core.designsystem.components.Knob
import com.studioone.mobile.core.designsystem.components.Taper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Compose UI tests for the core input widgets. Knob/Fader semantics are what
 * TalkBack users get, so they are part of the contract, not decoration.
 */
class KnobTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun knobExposesPercentageState() {
        compose.setContent {
            com.studioone.mobile.core.designsystem.theme.StudioOneTheme {
                Knob(value = 0.5f, onValueChange = {}, label = "Cutoff")
            }
        }
        compose.onNodeWithContentDescription("Cutoff").assert(hasStateDescription("50 percent"))
    }

    @Test
    fun swipeUpIncreasesValue() {
        var value = 0.5f
        compose.setContent {
            com.studioone.mobile.core.designsystem.theme.StudioOneTheme {
                Knob(value = value, onValueChange = { value = it }, label = "Drive")
            }
        }
        compose.onNodeWithContentDescription("Drive").performTouchInput {
            swipeUp()
        }
        assertTrue("swipe up should increase knob value", value > 0.5f)
    }

    @Test
    fun valueStaysInRangeUnderLargeDrag() {
        var value = 0.99f
        compose.setContent {
            com.studioone.mobile.core.designsystem.theme.StudioOneTheme {
                Knob(value = value, onValueChange = { value = it }, label = "Mix")
            }
        }
        compose.onNodeWithContentDescription("Mix").performTouchInput {
            swipeUp(durationMillis = 2000)
        }
        assertTrue(value <= 1f)
    }
}
