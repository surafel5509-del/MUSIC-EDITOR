package com.studioone.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studioone.core.designsystem.theme.ColorVisionAssist
import com.studioone.core.designsystem.theme.StudioOneTheme
import com.studioone.core.designsystem.theme.StudioThemeOptions
import com.studioone.core.domain.repository.ColorVisionMode
import com.studioone.core.domain.repository.SettingsRepository
import com.studioone.core.domain.repository.ThemeMode
import com.studioone.mobile.navigation.StudioOneNavHost
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val uiSettings by settingsRepository.observeUiSettings()
                .collectAsStateWithLifecycle(
                    initial = com.studioone.core.domain.repository.UiSettings(),
                )

            val darkTheme = when (uiSettings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }

            StudioOneTheme(
                darkTheme = darkTheme,
                options = StudioThemeOptions(
                    highContrast = uiSettings.highContrast,
                    colorVisionAssist = when (uiSettings.colorVisionMode) {
                        ColorVisionMode.NONE -> ColorVisionAssist.NONE
                        ColorVisionMode.PROTANOPIA -> ColorVisionAssist.PROTANOPIA
                        ColorVisionMode.DEUTERANOPIA -> ColorVisionAssist.DEUTERANOPIA
                        ColorVisionMode.TRITANOPIA -> ColorVisionAssist.TRITANOPIA
                    },
                    largeTouchTargets = uiSettings.largeTouchTargets,
                ),
            ) {
                StudioOneNavHost()
            }
        }
    }
}
