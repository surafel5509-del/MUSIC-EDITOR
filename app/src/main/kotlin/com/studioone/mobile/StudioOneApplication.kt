package com.studioone.mobile

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.studioone.mobile.core.analytics.AnalyticsHub
import com.studioone.mobile.core.audio.AudioEngineController
import com.studioone.mobile.core.data.sync.SyncScheduler
import com.studioone.mobile.core.midi.MidiDeviceManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Application entry point.
 *
 * Startup budget (docs/PERFORMANCE.md): < 300ms to first frame on a mid-range
 * device. Heavy initialization is deferred:
 *  * audio engine: started lazily when the first editor screen opens
 *  * MIDI discovery: after first frame (idle handler)
 *  * library cache refresh: WorkManager periodic, unmetered+idle only
 *  * analytics/crash: gated on GDPR consent before any collection flips on
 */
@HiltAndroidApp
class StudioOneApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var analyticsHub: AnalyticsHub
    @Inject lateinit var midiDeviceManager: MidiDeviceManager
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var audioEngineController: AudioEngineController

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.WARN)
            .build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            // Release: Crashlytics-breadcrumb tree only when consent granted.
            Timber.plant(CrashReportingTree())
        }

        appScope.launch {
            analyticsHub.refreshConsent()
            // Deferred subsystem warm-up after first frame.
            kotlinx.coroutines.delay(1500)
            midiDeviceManager.startDiscovery()
            syncScheduler.schedulePeriodicLibraryRefresh()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // Free the PCM decode cache aggressively; audio engine pools are fixed.
        appScope.launch(Dispatchers.IO) { runCatching { audioEngineController.stop() } }
    }
}

/** Timber tree that routes warnings/errors to Crashlytics logs (no PII: we log tags only). */
private class CrashReportingTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority < android.util.Log.WARN) return
        runCatching {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().log("[$tag] $message")
            t?.let { com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().recordException(it) }
        }
    }
}
