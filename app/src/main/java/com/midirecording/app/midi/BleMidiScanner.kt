package com.midirecording.app.midi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class BleMidiCandidate(val device: BluetoothDevice, val name: String)

/** Busca periféricos Bluetooth LE que anuncian el servicio MIDI estándar. */
class BleMidiScanner(context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val handler = Handler(Looper.getMainLooper())

    private val _results = MutableStateFlow<List<BleMidiCandidate>>(emptyList())
    val results: StateFlow<List<BleMidiCandidate>> = _results.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    private val callback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (_results.value.none { it.device.address == device.address }) {
                val name = result.scanRecord?.deviceName ?: device.name ?: device.address
                _results.value = _results.value + BleMidiCandidate(device, name)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _scanning.value = false
        }
    }

    private val stopRunnable = Runnable { stop() }

    /** Devuelve false si el Bluetooth no está disponible o apagado. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return false
        if (_scanning.value) return true
        _results.value = emptyList()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(MIDI_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        return try {
            scanner.startScan(listOf(filter), settings, callback)
            _scanning.value = true
            handler.postDelayed(stopRunnable, SCAN_MILLIS)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        handler.removeCallbacks(stopRunnable)
        if (!_scanning.value) return
        _scanning.value = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
    }

    companion object {
        val MIDI_SERVICE: UUID = UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
        const val SCAN_MILLIS = 15_000L
    }
}
