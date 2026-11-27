package com.midirecording.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
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
    val metronomeOn by vm.metronomeOn.collectAsStateWithLifecycle()
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    val peaks by vm.peaks.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var confirmNew by remember { mutableStateOf(false) }

    val openMidi = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importMidi) }
    val saveMidi = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/midi")) { it?.let(vm::exportMidi) }
    val openAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importAudio) }
    val saveWav = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        uri?.let { vm.exportMix(it, mp3 = false) }
    }
    val saveMp3 = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mpeg")) { uri ->
        uri?.let { vm.exportMix(it, mp3 = true) }
    }

    var showDevices by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(false) }
    var showSoundFonts by remember { mutableStateOf(false) }
    var instrumentFor by remember { mutableStateOf<Track?>(null) }
    var renameFor by remember { mutableStateOf<Track?>(null) }
    var showBpmDialog by remember { mutableStateOf(false) }
    /** null = cerrado; trackId = cuantizar esa pista entera; -1 = cuantizar la selección. */
    var quantizeTarget by remember { mutableStateOf<Long?>(null) }

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
                metronomeOn = metronomeOn,
                onMetronome = vm::toggleMetronome,
                canUndo = canUndo,
                onUndo = vm::undo,
                onDevices = {
                    vm.refreshDevices()
                    showDevices = true
                },
                fileMenu = {
                    FileMenu(
                        onNew = { confirmNew = true },
                        onImportMidi = { openMidi.launch(arrayOf("audio/midi", "audio/x-midi", "audio/mid", "application/x-midi", "*/*")) },
                        onExportMidi = { saveMidi.launch("proyecto.mid") },
                        onImportAudio = { openAudio.launch(arrayOf("audio/*")) },
                        onExportWav = { saveWav.launch("mezcla.wav") },
                        onExportMp3 = { saveMp3.launch("mezcla.mp3") },
                    )
                },
            )
            busy?.let { text ->
                Column(Modifier.fillMaxWidth().background(AppColors.Toolbar).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(text, color = Color.White, fontSize = 13.sp)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
            Box(Modifier.fillMaxSize()) {
                Timeline(
                    project = project,
                    peaks = peaks,
                    recordingNotes = recordingNotes,
                    transport = transport,
                    positionFlow = vm.position,
                    cursorTick = cursor,
                    selection = selection,
                    onSetCursor = vm::setCursor,
                    onSelect = vm::setSelection,
                    onSelectRow = vm::selectRow,
                    onToggleMute = vm::toggleMuteAll,
                    onToggleSolo = vm::toggleSoloAll,
                    onInstrument = { instrumentFor = it },
                    onRename = { renameFor = it },
                    onQuantize = { quantizeTarget = it.id },
                    onDelete = { vm.deleteTrack(it.id) },
                    onUngroup = vm::ungroup,
                    modifier = Modifier.fillMaxSize(),
                )
                val sel = selection
                if (sel != null && transport != TransportState.RECORDING) {
                    SelectionBar(
                        selection = sel,
                        onQuantize = { quantizeTarget = -1L },
                        onGroup = vm::groupSelected,
                        onDeleteNotes = vm::deleteSelectedNotes,
                        onClear = { vm.setSelection(null) },
                        modifier = Modifier.align(Alignment.BottomStart),
                    )
                }
                if (project.tracks.isEmpty() && transport != TransportState.RECORDING) {
                    Text(
                        text = if (devices.any { it.isOpen && it.canRecord }) {
                            "Pulsa ● para grabar desde tu dispositivo MIDI"
                        } else {
                            "Conecta un teclado o controlador MIDI por USB (OTG) o Bluetooth.\nToca el icono del piano para buscar uno Bluetooth."
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
        DevicesDialog(
            vm = vm,
            onOpenControls = {
                vm.stopBleScan()
                showDevices = false
                showControls = true
            },
            onOpenSoundFonts = {
                vm.stopBleScan()
                showDevices = false
                showSoundFonts = true
            },
            onDismiss = {
                vm.stopBleScan()
                showDevices = false
            },
        )
    }
    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            title = { Text("Nuevo proyecto") },
            text = { Text("Se quitarán todas las pistas. Si te equivocas, puedes pulsar Deshacer.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.newProject()
                    confirmNew = false
                }) { Text("Empezar de cero") }
            },
            dismissButton = { TextButton(onClick = { confirmNew = false }) { Text("Cancelar") } },
        )
    }
    if (showSoundFonts) {
        SoundFontDialog(vm = vm, onDismiss = { showSoundFonts = false })
    }
    if (showControls) {
        ControlsDialog(vm = vm, onDismiss = { showControls = false })
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
    quantizeTarget?.let { target ->
        QuantizeDialog(
            title = if (target == -1L) "Cuantizar selección" else "Cuantizar pista entera",
            onConfirm = { grid, ends ->
                if (target == -1L) vm.quantizeSelection(grid, ends) else vm.quantizeTrack(target, grid, ends)
                quantizeTarget = null
            },
            onDismiss = { quantizeTarget = null },
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
