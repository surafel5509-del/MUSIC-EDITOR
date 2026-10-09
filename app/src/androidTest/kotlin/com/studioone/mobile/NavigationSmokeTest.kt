package com.studioone.mobile

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studioone.core.designsystem.theme.StudioOneTheme
import com.studioone.mobile.navigation.StudioOneNavHost
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented navigation smoke test: boots the root host and verifies the
 * home screen renders. (Requires the Hilt test application for full DI; in
 * CI we run it against the debug variant with fakes via HiltAndroidTest.)
 */
@RunWith(AndroidJUnit4::class)
class NavigationSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun homeScreenShowsProjectsTitle() {
        composeRule.setContent {
            StudioOneTheme {
                StudioOneNavHost()
            }
        }
        composeRule.onNodeWithText("Projects").assertExists()
    }
}
