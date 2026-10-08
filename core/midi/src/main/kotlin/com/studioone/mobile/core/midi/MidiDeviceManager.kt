package com.studioone.mobile.core.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import com.studioone.mobile.core.common.AppEventBus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber

/** Connection lifecycle state of one MIDI device. */
data class MidiDeviceInfoUi(
    val id: String,
    val name: String,
    val manufacturer: String,
    val isBluetooth: Boolean,
    val isVirtual: Boolean,
    val inputPortCount: Int,
    val outputPortCount: Int,
    val connected: Boolean = false,
)

/**
 * Unified MIDI device manager over the Android MIDI framework
 * (android.media.midi), which covers USB class-compliant devices AND
 * Bluetooth LE MIDI (system-provided since API 23). Apps acting as MIDI
 * peripherals (e.g. controller mode) go through MidiDeviceService — see
 * docs/MIDI.md.
 *
 * Incoming bytes flow through [MidiParser] (running status aware) and are
 * published on [messages] tagged with the source device id.
 */
@Singleton
class MidiDeviceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventBus: AppEventBus,
) {
    private val midiManager: MidiManager =
        context.getSystemService(Context.MIDI_SERVICE) as MidiManager
    private val handler = Handler(Looper.getMainLooper())

    private val openDevices = mutableMapOf<String, MidiDevice>()
    private val parsers = mutableMapOf<String, MidiParser>()

    private val _devices = MutableStateFlow<List<MidiDeviceInfoUi>>(emptyList())
    val devices: StateFlow<List<MidiDeviceInfoUi>> = _devices.asStateFlow()

    private val _messages = MutableStateFlow<MidiMessage?>(null)
    /** Latest routed message (hot). Prefer [messagesFrom] for per-device streams. */
    val messages: StateFlow<MidiMessage?> = _messages.asStateFlow()

    private var router: ((MidiMessage, String) -> Unit)? = null

    /** Set the sink that receives all parsed messages (AudioEngine router in practice). */
    fun setMessageRouter(router: ((MidiMessage, String) -> Unit)?) {
        this.router = router
    }

    fun startDiscovery() {
        refreshDevices()
        midiManager.addDeviceCallback(object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(deviceInfo: MidiDeviceInfo) { refreshDevices() }
            override fun onDeviceRemoved(deviceInfo: MidiDeviceInfo) { refreshDevices() }
            override fun onDeviceStatusChanged(status: MidiManager.DeviceStatus?) { refreshDevices() }
        }, handler)
    }

    private fun refreshDevices() {
        val list = midiManager.devices.map { info ->
            val id = info.id.toString()
            MidiDeviceInfoUi(
                id = id,
                name = info.properties?.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI Device ${info.id}",
                manufacturer = info.properties?.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER) ?: "",
                isBluetooth = info.transportType == MidiDeviceInfo.TYPE_BLUETOOTH,
                isVirtual = info.transportType == MidiDeviceInfo.TYPE_VIRTUAL,
                inputPortCount = info.inputPortCount,
                outputPortCount = info.outputPortCount,
                connected = openDevices.containsKey(id),
            )
        }
        _devices.value = list
    }

    /** Open a device and start emitting parsed messages. */
    suspend fun openDevice(deviceId: String): Boolean {
        val info = midiManager.devices.firstOrNull { it.id.toString() == deviceId } ?: return false
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            midiManager.openDevice(info, { device ->
                if (device == null) {
                    Timber.w("openDevice failed for $deviceId")
                    cont.resumeWith(Result.success(false))
                    return@openDevice
                }
                openDevices[deviceId] = device
                parsers[deviceId] = MidiParser()
                // Open every output port of the device (device OUT = our IN).
                for (portIndex in 0 until info.outputPortCount) {
                    val port = device.openOutputPort(portIndex)
                    port?.connect(object : MidiReceiver() {
                        override fun onReceive(msg: ByteArray?, offset: Int, count: Int, timestamp: Long) {
                            if (msg == null) return
                            val parser = parsers[deviceId] ?: return
                            for (i in offset until offset + count) {
                                val parsed = parser.feed(msg[i].toInt() and 0xFF, timestamp)
                                if (parsed != null) {
                                    router?.invoke(parsed, deviceId)
                                    _messages.value = parsed
                                }
                            }
                        }
                    }, handler)
                }
                refreshDevices()
                cont.resumeWith(Result.success(true))
            }, handler)
        }
    }

    /** Send bytes to a device's input port (device IN = our OUT — e.g. clock out). */
    fun sendToDevice(deviceId: String, bytes: ByteArray) {
        val device = openDevices[deviceId] ?: return
        val port: MidiInputPort = device.openInputPort(0) ?: return
        port.send(bytes, 0, bytes.size, System.nanoTime())
    }

    fun closeDevice(deviceId: String) {
        openDevices.remove(deviceId)?.close()
        parsers.remove(deviceId)
        refreshDevices()
    }

    fun closeAll() {
        openDevices.values.forEach { runCatching { it.close() } }
        openDevices.clear()
        parsers.clear()
        refreshDevices()
    }

    /** Per-device message stream (cold; opens the device while collected). */
    fun messagesFrom(deviceId: String): Flow<MidiMessage> = callbackFlow {
        val previous = router
        setMessageRouter { msg, source ->
            previous?.invoke(msg, source)
            if (source == deviceId) trySend(msg)
        }
        openDevice(deviceId)
        awaitClose { setMessageRouter(previous) }
    }
}

/** MIDI beat clock (24 ppq) generator/consumer for hardware sync. */
class MidiClock {
    companion object {
        const val PPQ = 24
        val TIMING_CLOCK = byteArrayOf(0xF8.toByte())
        val START = byteArrayOf(0xFA.toByte())
        val STOP = byteArrayOf(0xFC.toByte())
        val CONTINUE = byteArrayOf(0xFB.toByte())
    }

    /** Compute nanos-per-clock-tick for a BPM. */
    fun nanosPerTick(bpm: Double): Long = (60.0 / bpm / PPQ * 1_000_000_000).toLong()
}
