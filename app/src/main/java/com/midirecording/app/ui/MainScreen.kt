package com.midirecording.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.midirecording.app.model.Track
import com.midirecording.app.ui.theme.AppColors

@Composable
fun MainScreen(vm: MainViewModel) {
    val project by vm.project.collectAsStateWithLifecycle()
    val transport by vm.transport.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val cursor by vm.cursor.collectAsStateWithLifecycle()
    val recordingNotes by vm.recordingNotes.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val inputActivity by vm.inputActivity.collectAsStateWithLifecycle()

    var showDevices by remember { mutableStateOf(false) }
    var instrumentFor by remember { mutableStateOf<Track?>(null) }
    var renameFor by remember { mutableStateOf<Track?>(null) }
    var showBpmDialog by remember { mutableStateOf(false) }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        containerColor = AppColors.Background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TransportBar(
                transport = transport,
                position = vm.position,
                project = project,
                connectedCount = devices.count { it.isOpen },
                inputActivity = inputActivity,
                onRecord = vm::record,
                onPlayPause = vm::playPause,
                onStop = vm::stop,
                onGoToStart = vm::goToStart,
                onBpmChange = vm::changeBpm,
                onBpmClick = { showBpmDialog = true },
                onDevices = {
                    vm.refreshDevices()
                    showDevices = true
                },
            )
            Box(Modifier.fillMaxSize()) {
                Timeline(
                    project = project,
                    recordingNotes = recordingNotes,
                    transport = transport,
                    positionFlow = vm.position,
                    cursorTick = cursor,
                    selection = selection,
                    onSetCursor = vm::setCursor,
                    onSelect = vm::setSelection,
                    onSelectTrack = vm::selectTrack,
                    onToggleMute = vm::toggleMute,
                    onToggleSolo = vm::toggleSolo,
                    onInstrument = { instrumentFor = it },
                    onRename = { renameFor = it },
                    onDelete = { vm.deleteTrack(it.id) },
                    modifier = Modifier.fillMaxSize(),
                )
                if (project.tracks.isEmpty() && transport != TransportState.RECORDING) {
                    Text(
                        text = if (devices.any { it.isOpen && it.canRecord }) {
                            "Pulsa ● para grabar desde tu dispositivo MIDI"
                        } else {
                            "Conecta un teclado o controlador MIDI por USB (OTG) o Bluetooth.\nToca el icono de dispositivos para buscar uno Bluetooth."
                        },
                        color = AppColors.Muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .background(AppColors.Background.copy(alpha = 0.6f))
                            .padding(24.dp),
                    )
                }
            }
        }
    }

    if (showDevices) {
        DevicesDialog(vm = vm, onDismiss = {
            vm.stopBleScan()
            showDevices = false
        })
    }
    instrumentFor?.let { track ->
        InstrumentDialog(
            track = track,
            onPick = { program, channel ->
                vm.setChannel(track.id, channel)
                vm.setProgram(track.id, program)
                instrumentFor = null
            },
            onDismiss = { instrumentFor = null },
        )
    }
    renameFor?.let { track ->
        TextInputDialog(
            title = "Renombrar pista",
            initial = track.name,
            onConfirm = {
                vm.renameTrack(track.id, it)
                renameFor = null
            },
            onDismiss = { renameFor = null },
        )
    }
    if (showBpmDialog) {
        TextInputDialog(
            title = "Tempo (BPM)",
            initial = formatBpm(project.bpm),
            numeric = true,
            onConfirm = { text ->
                text.replace(',', '.').toDoubleOrNull()?.let { vm.setBpm(it) }
                showBpmDialog = false
            },
            onDismiss = { showBpmDialog = false },
        )
    }
}

fun formatBpm(bpm: Double): String =
    if (bpm % 1.0 == 0.0) bpm.toInt().toString() else "%.1f".format(bpm)
