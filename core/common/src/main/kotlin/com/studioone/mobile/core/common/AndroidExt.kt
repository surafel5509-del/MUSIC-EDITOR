package com.studioone.mobile.core.common

import android.content.Context
import android.os.Build

/** Device capability probing — the engine & settings use these to pick safe defaults. */
object DeviceProfile {
    /** Rough device class used for default buffer size, polyphony, and FX budget. */
    enum class PerformanceClass { LOW, MID, HIGH }

    fun classify(context: Context): PerformanceClass {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val cores = Runtime.getRuntime().availableProcessors()
        val ramMb = activityManager?.let { am ->
            android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                .totalMem / (1024 * 1024)
        } ?: 2048L
        val isLowRam = activityManager?.isLowRamDevice ?: false
        return when {
            isLowRam || cores <= 4 || ramMb < 3_000 -> PerformanceClass.LOW
            cores <= 6 || ramMb < 6_000 -> PerformanceClass.MID
            else -> PerformanceClass.HIGH
        }
    }

    /** Android 11+ exposes a platform performance class; prefer it when present. */
    val platformClass: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val c = Class.forName("android.os.Build\$VERSION")
                // Build.VERSION.MEDIA_PERFORMANCE_CLASS is @SystemApi-ish; reflect safely.
                c.getField("MEDIA_PERFORMANCE_CLASS").getInt(null)
            } catch (_: Throwable) { 0 }
        } else 0

    val supportsAaudioMmap: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O // API 26: AAudio available
    val supportsBluetoothMidi: Boolean get() = Build.VERSION.SDK_INT >= 23
    val supportsUsbMidi: Boolean get() = Build.VERSION.SDK_INT >= 23
    val supportsHiResOutput: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            android.media.AudioManager.getPropertyStaticSafe() // see below
    val sdkInt: Int get() = Build.VERSION.SDK_INT
}

private fun android.media.AudioManager.getPropertyStaticSafe(): Boolean = true // hi-res probing done natively
