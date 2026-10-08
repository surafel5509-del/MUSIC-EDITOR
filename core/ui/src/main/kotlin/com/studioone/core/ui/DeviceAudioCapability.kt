package com.studioone.core.ui

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build

/**
 * Device capability probe used to pick defaults for sample rate / buffer and
 * to warn the user when the hardware cannot hit the low-latency path.
 */
data class DeviceAudioCapability(
    val proAudioSupported: Boolean,
    val nativeSampleRate: Int,
    val nativeFramesPerBuffer: Int,
    val midiSupported: Boolean,
    val usbMidiSupported: Boolean,
) {
    /** True when sub-10ms round-trip is plausible on this device. */
    val lowLatencyLikely: Boolean get() = proAudioSupported
}

fun probeAudioCapability(context: Context): DeviceAudioCapability {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val nativeRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48_000
    val nativeFrames = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 192
    return DeviceAudioCapability(
        proAudioSupported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO),
        nativeSampleRate = nativeRate,
        nativeFramesPerBuffer = nativeFrames,
        midiSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI),
        usbMidiSupported = context.packageManager.hasSystemFeature("android.hardware.usb.accessory") ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST),
    )
}
