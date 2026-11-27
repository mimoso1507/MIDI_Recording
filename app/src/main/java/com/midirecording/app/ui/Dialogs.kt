package com.midirecording.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.midirecording.app.midi.MidiDeviceEntry
import com.midirecording.app.midi.Transport
import com.midirecording.app.model.GM_INSTRUMENTS
import com.midirecording.app.model.Track
import com.midirecording.app.ui.theme.AppColors

@Composable
fun DevicesDialog(vm: MainViewModel, onOpenControls: () -> Unit, onDismiss: () -> Unit) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val output by vm.output.collectAsStateWithLifecycle()
    val bleResults by vm.bleResults.collectAsStateWithLifecycle()
    val scanning by vm.bleScanning.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissions = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) vm.startBleScan()
    }
    fun scan() {
        val granted = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) vm.startBleScan() else launcher.launch(permissions)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Listo") } },
        title = { Text("Dispositivos MIDI") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SectionTitle("Conectados")
                val connected = devices.filter { it.isOpen }
                if (connected.isEmpty()) {
                    Text(
                        "Ninguno todavía. Conecta un teclado por USB (con adaptador OTG) o búscalo por Bluetooth abajo.",
                        color = AppColors.Muted,
                        fontSize = 13.sp,
                    )
                }
                connected.forEach { DeviceRow(it) }

                if (connected.isNotEmpty()) {
                    OutlinedButton(onClick = onOpenControls) {
                        Icon(Icons.Filled.Gamepad, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Usar botones del controlador (aprender)")
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                SectionTitle("Salida de reproducción")
                OutputOption(
                    label = "El mismo dispositivo con el que tocas (recomendado)",
                    selected = output == OutputTarget.SameAsInput,
                    onClick = { vm.setOutput(OutputTarget.SameAsInput) },
                )
                devices.filter { it.isOpen && it.canPlay }.forEach { device ->
                    OutputOption(
                        label = device.name,
                        icon = iconFor(device.transport),
                        selected = output == OutputTarget.Device(device.id),
                        onClick = { vm.setOutput(OutputTarget.Device(device.id)) },
                    )
                }
                OutputOption(
                    label = "Auriculares o altavoz del teléfono (próximamente)",
                    icon = Icons.Filled.Headphones,
                    selected = output == OutputTarget.InternalSynth,
                    enabled = false,
                    onClick = {},
                )

                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                SectionTitle("Bluetooth MIDI")
                if (scanning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Buscando… enciende el modo Bluetooth de tu teclado", fontSize = 13.sp)
                    }
                } else {
                    OutlinedButton(onClick = ::scan) {
                        Icon(Icons.Filled.Bluetooth, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Buscar dispositivos Bluetooth")
                    }
                }
                bleResults.forEach { candidate ->
                    val alreadyOpen = connected.any { it.name == candidate.name && it.transport == Transport.BLUETOOTH }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Bluetooth, null, tint = AppColors.RulerText)
                        Spacer(Modifier.width(8.dp))
                        Text(candidate.name, Modifier.weight(1f))
                        if (alreadyOpen) {
                            Text("Conectado", color = AppColors.Playhead, fontSize = 12.sp)
                        } else {
                            Button(onClick = { vm.connectBle(candidate) }) { Text("Conectar") }
                        }
                    }
                }
            }
        },
    )
}

private fun iconFor(transport: Transport): ImageVector = when (transport) {
    Transport.USB -> Icons.Filled.Usb
    Transport.BLUETOOTH -> Icons.Filled.Bluetooth
    Transport.VIRTUAL -> Icons.Filled.Cable
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
}

@Composable
private fun DeviceRow(device: MidiDeviceEntry) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(iconFor(device.transport), null, tint = AppColors.Playhead)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(device.name, fontSize = 14.sp)
            val caps = listOfNotNull(
                "graba".takeIf { device.canRecord },
                "reproduce".takeIf { device.canPlay },
            ).joinToString(" y ")
            Text(
                when (device.transport) {
                    Transport.USB -> "USB"
                    Transport.BLUETOOTH -> "Bluetooth"
                    Transport.VIRTUAL -> "Virtual"
                } + if (caps.isNotEmpty()) " · $caps" else "",
                fontSize = 12.sp,
                color = AppColors.Muted,
            )
        }
    }
}

@Composable
private fun OutputOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) AppColors.RulerText else AppColors.Muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, fontSize = 13.sp, color = if (enabled) Color.White else AppColors.Muted)
    }
}

@Composable
fun InstrumentDialog(track: Track, onPick: (program: Int?, channel: Int) -> Unit, onDismiss: () -> Unit) {
    var channel by remember { mutableIntStateOf(track.channel) }
    var program by remember { mutableStateOf(track.program) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = ((track.program ?: -1) + 1).coerceAtLeast(0))
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(program, channel) }) { Text("Aceptar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        title = { Text(track.name) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Canal MIDI", Modifier.weight(1f))
                    IconButton(onClick = { channel = (channel - 1).coerceAtLeast(0) }) { Icon(Icons.Filled.Remove, "Canal anterior") }
                    Text("${channel + 1}", fontWeight = FontWeight.Bold)
                    IconButton(onClick = { channel = (channel + 1).coerceAtMost(15) }) { Icon(Icons.Filled.Add, "Canal siguiente") }
                }
                Text(
                    "El instrumento se envía a tu teclado al empezar a reproducir.",
                    color = AppColors.Muted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.size(8.dp))
                LazyColumn(Modifier.heightIn(max = 320.dp), state = listState) {
                    item {
                        InstrumentRow("Sin cambio (usar el sonido actual del teclado)", program == null) { program = null }
                    }
                    itemsIndexed(GM_INSTRUMENTS) { index, name ->
                        InstrumentRow("${index + 1}. $name", program == index) { program = index }
                    }
                }
            }
        },
    )
}

@Composable
private fun InstrumentRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 14.sp,
        color = if (selected) Color.Black else Color.White,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) AppColors.Playhead else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    )
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    numeric: Boolean = false,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Aceptar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
                ),
            )
        },
    )
}
