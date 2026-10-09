package com.studioone.midi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A BLE MIDI peripheral found during scanning. */
data class BleMidiDevice(val address: String, val name: String, val rssi: Int)

/**
 * Scans for BLE MIDI peripherals (service 03B80E5A-EDE8-4B33-A751-6CE34EC4C700).
 * Pairing is completed through the system MIDI settings once the transport
 * layer binds the device; this scanner only surfaces candidates.
 */
@Singleton
class BleMidiScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        val BLE_MIDI_SERVICE_UUID: UUID =
            UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
    }

    @SuppressLint("MissingPermission")
    fun scan(): Flow<BleMidiDevice> = callbackFlow {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val scanner = bluetoothManager?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            close()
            return@callbackFlow
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BLE_MIDI_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(
                    BleMidiDevice(
                        address = result.device.address,
                        name = result.device.name ?: result.scanRecord?.deviceName ?: "Unknown",
                        rssi = result.rssi,
                    ),
                )
            }
        }
        scanner.startScan(listOf(filter), settings, callback)
        awaitClose { scanner.stopScan(callback) }
    }
}
