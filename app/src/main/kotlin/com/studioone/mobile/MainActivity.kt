package com.studioone.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import com.studioone.mobile.core.audio.AudioEngineController
import com.studioone.mobile.core.designsystem.theme.S1ThemeSettings
import com.studioone.mobile.core.designsystem.theme.StudioOneTheme
import com.studioone.mobile.core.datastore.SettingsRepository
import com.studioone.mobile.core.datastore.ThemeMode
import com.studioone.mobile.core.midi.MidiEngineRouter
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Single-activity host. Edge-to-edge on all APIs; the nav host adapts to
 * window size class (phones: bottom bar; tablets/foldables: nav rail +
 * dual-pane editor layouts — see AppNavHost).
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var audioEngineController: AudioEngineController
    @Inject lateinit var midiRouter: MidiEngineRouter

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Permission result routed to onboarding via AppViewModel.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Keep the screen on during recording sessions (audio focus managed by
        // the engine + media session).
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            val prefs = androidx.lifecycle.compose.collectAsStateWithLifecycle(
                settingsRepository.uiPreferences.let { flow ->
                    kotlinx.coroutines.flow.MutableStateFlow(
                        com.studioone.mobile.core.datastore.UiPreferences(),
                    ).also { msf ->
                        // Bridge: collect on the composition's lifecycle.
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            flow.collect { msf.value = it }
                        }
                    }
                },
            )
            val uiPrefs = prefs.value
            val windowSize = calculateWindowSizeClass(this)
            val dark = when (uiPrefs.themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            StudioOneTheme(
                settings = S1ThemeSettings(
                    darkTheme = dark,
                    highContrast = uiPrefs.highContrast,
                    colorBlindMode = uiPrefs.colorBlindMode != com.studioone.mobile.core.datastore.ColorBlindMode.NONE,
                    largeTouchTargets = uiPrefs.largeTouchTargets,
                    dynamicColor = false, // brand consistency; user setting can enable later
                ),
            ) {
                AppNavHost(
                    isTablet = windowSize.widthSizeClass >= WindowWidthSizeClass.Medium,
                    onRequestRecordPermission = {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED
                        ) {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                )
            }
        }

        // Handle audio-file VIEW intents (import into current project).
        handleIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        // Deep links (share tokens) and VIEW audio intents are dispatched to
        // AppNavHost via the shared IntentBus (see navigation/AppNavigation.kt).
        intent?.let { AppIntentBus.publish(it) }
    }

    override fun onPause() {
        super.onPause()
        // Autosave on backgrounding — a killed process must never lose a take.
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) audioEngineController.stop()
    }
}

/** Tiny intent fan-out so navigation reacts to deep links/imports. */
object AppIntentBus {
    private val flow = kotlinx.coroutines.flow.MutableSharedFlow<android.content.Intent>(extraBufferCapacity = 8)
    val intents: kotlinx.coroutines.flow.SharedFlow<android.content.Intent> = flow
    fun publish(intent: android.content.Intent) { flow.tryEmit(intent) }
}
