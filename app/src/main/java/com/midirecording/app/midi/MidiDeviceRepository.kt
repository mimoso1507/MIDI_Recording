package com.midirecording.app.midi

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiDeviceStatus
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

enum class Transport { USB, BLUETOOTH, VIRTUAL }

data class MidiDeviceEntry(
    val id: Int,
    val name: String,
    val transport: Transport,
    /** El dispositivo tiene puertos de entrada: podemos enviarle notas (reproducir). */
    val canPlay: Boolean,
    /** El dispositivo tiene puertos de salida: podemos grabar lo que toca. */
    val canRecord: Boolean,
    val isOpen: Boolean,
)

/**
 * Abre automáticamente todos los dispositivos MIDI conectados (USB OTG,
 * Bluetooth LE y virtuales), escucha todos sus puertos a la vez y permite
 * enviar mensajes a cualquiera de ellos.
 */
class MidiDeviceRepository(
    context: Context,
    private val onEvent: (deviceId: Int, event: MidiEvent, timestampNanos: Long) -> Unit,
) {
    private val midiManager: MidiManager? = context.getSystemService(MidiManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private class OpenDevice(
        val device: MidiDevice,
        val outputPorts: List<MidiOutputPort>,
        var inputPort: MidiInputPort? = null,
    )

    private val opened = ConcurrentHashMap<Int, OpenDevice>()
    private val pending = ConcurrentHashMap.newKeySet<Int>()
    /** Conexiones Bluetooth extra que mantenemos vivas. */
    private val bluetoothHandles = ConcurrentHashMap<Int, MutableList<MidiDevice>>()

    private val _devices = MutableStateFlow<List<MidiDeviceEntry>>(emptyList())
    val devices: StateFlow<List<MidiDeviceEntry>> = _devices.asStateFlow()

    val isMidiSupported: Boolean get() = midiManager != null

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) {
            open(device)
            refresh()
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            close(device.id)
            refresh()
        }

        override fun onDeviceStatusChanged(status: MidiDeviceStatus) {
            refresh()
        }
    }

    @Suppress("DEPRECATION")
    fun start() {
        val mm = midiManager ?: return
        mm.registerDeviceCallback(callback, handler)
        allInfos().forEach { open(it) }
        refresh()
    }

    @Suppress("DEPRECATION")
    fun release() {
        midiManager?.unregisterDeviceCallback(callback)
        opened.keys.toList().forEach { close(it) }
        bluetoothHandles.values.flatten().forEach { runCatching { it.close() } }
        bluetoothHandles.clear()
    }

    @Suppress("DEPRECATION")
    private fun allInfos(): List<MidiDeviceInfo> {
        val mm = midiManager ?: return emptyList()
        return if (Build.VERSION.SDK_INT >= 33) {
            mm.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).toList()
        } else {
            mm.devices.toList()
        }
    }

    private fun open(info: MidiDeviceInfo) {
        val mm = midiManager ?: return
        if (opened.containsKey(info.id) || !pending.add(info.id)) return
        mm.openDevice(info, { device ->
            pending.remove(info.id)
            if (device != null) attach(device)
        }, handler)
    }

    /** Conecta un periférico Bluetooth LE MIDI encontrado con [BleMidiScanner]. */
    fun connectBluetooth(device: BluetoothDevice) {
        val mm = midiManager ?: return
        mm.openBluetoothDevice(device, { midiDevice ->
            if (midiDevice != null) attach(midiDevice, fromBluetooth = true)
        }, handler)
    }

    private fun attach(device: MidiDevice, fromBluetooth: Boolean = false) {
        val info = device.info
        if (opened.containsKey(info.id)) {
            // Ya estaba abierto por otra vía. Si viene de Bluetooth lo
            // mantenemos abierto para no cortar la conexión.
            if (fromBluetooth) {
                bluetoothHandles.getOrPut(info.id) { mutableListOf() }.add(device)
            } else {
                runCatching { device.close() }
            }
            return
        }
        val ports = (0 until info.outputPortCount).mapNotNull { index ->
            device.openOutputPort(index)?.also { port ->
                val parser = MidiParser { event, ts -> onEvent(info.id, event, ts) }
                port.connect(object : MidiReceiver() {
                    override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                        parser.feed(msg, offset, count, if (timestamp == 0L) System.nanoTime() else timestamp)
                    }
                })
            }
        }
        opened[info.id] = OpenDevice(device, ports)
        refresh()
    }

    private fun close(id: Int) {
        opened.remove(id)?.let { od ->
            od.outputPorts.forEach { runCatching { it.close() } }
            runCatching { od.inputPort?.close() }
            runCatching { od.device.close() }
        }
        bluetoothHandles.remove(id)?.forEach { runCatching { it.close() } }
    }

    fun refresh() {
        _devices.value = allInfos().map { info ->
            MidiDeviceEntry(
                id = info.id,
                name = nameOf(info),
                transport = when (info.type) {
                    MidiDeviceInfo.TYPE_USB -> Transport.USB
                    MidiDeviceInfo.TYPE_BLUETOOTH -> Transport.BLUETOOTH
                    else -> Transport.VIRTUAL
                },
                canPlay = info.inputPortCount > 0,
                canRecord = info.outputPortCount > 0,
                isOpen = opened.containsKey(info.id),
            )
        }.sortedBy { it.transport.ordinal }
    }

    fun nameOf(id: Int): String? = _devices.value.firstOrNull { it.id == id }?.name

    /** Envía un mensaje al dispositivo. Devuelve false si no se pudo. */
    fun send(deviceId: Int, data: ByteArray): Boolean {
        val od = opened[deviceId] ?: return false
        val port = synchronized(od) {
            od.inputPort ?: if (od.device.info.inputPortCount > 0) {
                od.device.openInputPort(0).also { od.inputPort = it }
            } else {
                null
            }
        } ?: return false
        return try {
            port.send(data, 0, data.size)
            true
        } catch (e: IOException) {
            Log.w(TAG, "No se pudo enviar MIDI", e)
            false
        }
    }

    private fun nameOf(info: MidiDeviceInfo): String {
        val p = info.properties
        return p.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: listOfNotNull(
                p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER),
                p.getString(MidiDeviceInfo.PROPERTY_PRODUCT),
            ).joinToString(" ").ifBlank { "Dispositivo MIDI ${info.id}" }
    }

    private companion object {
        const val TAG = "MidiDevices"
    }
}
