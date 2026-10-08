package com.studioone.mobile.core.audio

/**
 * Meter polling helpers exposed to feature modules without leaking the
 * internal NativeAudioEngine object.
 */
object NativeMeters {
    private val buf = FloatArray(6)

    fun pullStrip(controller: AudioEngineController, handle: Int, out: FloatArray): Boolean {
        // The controller polls via its telemetry loop for master meters; strip
        // meters are pulled on demand by the mixer UI thread (cheap atomic reads).
        return controller.pullStripMeters(handle, out)
    }
}
