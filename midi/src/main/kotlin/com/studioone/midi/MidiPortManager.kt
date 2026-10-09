package com.studioone.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A discovered MIDI device (USB or BLE) available to the app. */
data class MidiDeviceEntry(
    val id: Int,
    val name: String,
    val manufacturer: String,
    val isBluetooth: Boolean,
    val inputPortCount: Int,
)

/**
 * Scans Android's MidiManager for USB/BLE MIDI devices and streams decoded
 * [MidiEvent]s from every open input port.
 */
@Singleton
class MidiPortManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val parser: MidiParser = MidiParser(),
) {
    private val midiManager: MidiManager? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
        } else null
    }

    private val events = MutableSharedFlow<MidiEvent>(extraBufferCapacity = 256)
    private val openPorts = mutableListOf<MidiInputPort>()
    private val openDevices = mutableListOf<MidiDevice>()

    /** Hot list of connected devices (USB + BLE). */
    val devices: Flow<List<MidiDeviceEntry>> = callbackFlow {
        val manager = midiManager
        if (manager == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val map = { infos: Array<MidiDeviceInfo> ->
            infos.map { info ->
                MidiDeviceEntry(
                    id = info.id,
                    name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI device ${info.id}",
                    manufacturer = info.properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER) ?: "",
                    isBluetooth = info.type == MidiDeviceInfo.TYPE_BLUETOOTH,
                    inputPortCount = info.inputPortCount,
                )
            }
        }
        trySend(map(manager.devices))
        val callback = object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(device: MidiDeviceInfo) {
                trySend(map(manager.devices))
            }
            override fun onDeviceRemoved(device: MidiDeviceInfo) {
                trySend(map(manager.devices))
            }
        }
        manager.registerDeviceCallback(callback, null)
        awaitClose { manager.unregisterDeviceCallback(callback) }
    }

    /** Stream of events from all opened ports. */
    fun events(): Flow<MidiEvent> = events.asSharedFlow()

    /** Opens every input port of [entry]; events flow into [events]. */
    fun openDevice(entry: MidiDeviceEntry) {
        val manager = midiManager ?: return
        val info = manager.devices.firstOrNull { it.id == entry.id } ?: return
        manager.openDevice(info, { device ->
            if (device == null) return@openDevice
            openDevices += device
            for (portInfo in info.ports) {
                if (portInfo.type != MidiDeviceInfo.PortInfo.TYPE_INPUT) continue
                val port = device.openInputPort(portInfo.portNumber) ?: continue
                port.connect(object : MidiReceiver() {
                    override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                        val slice = msg.copyOfRange(offset, offset + count.toInt())
                        parser.feed(slice, slice.size, timestamp).forEach { event ->
                            events.tryEmit(event)
                        }
                    }
                })
                openPorts += port
            }
        }, null)
    }

    fun closeAll() {
        openPorts.forEach { runCatching { it.close() } }
        openDevices.forEach { runCatching { it.close() } }
        openPorts.clear()
        openDevices.clear()
    }
}
