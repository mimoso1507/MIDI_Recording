package com.midirecording.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.midirecording.app.data.ProjectStore
import com.midirecording.app.engine.Metronome
import com.midirecording.app.engine.MidiOut
import com.midirecording.app.engine.QuantizeGrid
import com.midirecording.app.engine.Quantizer
import com.midirecording.app.engine.Player
import com.midirecording.app.engine.Recorder
import com.midirecording.app.midi.BleMidiCandidate
import com.midirecording.app.midi.BleMidiScanner
import com.midirecording.app.midi.ControlAction
import com.midirecording.app.midi.ControlMapper
import com.midirecording.app.midi.MidiTrigger
import com.midirecording.app.midi.toBytes
import com.midirecording.app.synth.SoundFont
import com.midirecording.app.synth.SoundFontLibrary
import com.midirecording.app.synth.SynthOutput
import com.midirecording.app.midi.MidiDeviceEntry
import com.midirecording.app.midi.MidiDeviceRepository
import com.midirecording.app.midi.MidiEvent
import com.midirecording.app.model.Note
import com.midirecording.app.model.PPQ
import com.midirecording.app.model.Project
import com.midirecording.app.model.TimeMath
import com.midirecording.app.model.Track
import com.midirecording.app.model.TrackGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class TransportState { STOPPED, PLAYING, PAUSED, RECORDING }

sealed interface OutputTarget {
    /** El mismo dispositivo desde el que se toca (por defecto). */
    data object SameAsInput : OutputTarget
    data class Device(val id: Int) : OutputTarget
    /** Sintetizador interno con SoundFont, por auriculares o altavoz. */
    data object InternalSynth : OutputTarget
}

/** Rango de tiempo seleccionado sobre una o varias pistas, como en Audacity. */
data class Selection(val startTick: Long, val endTick: Long, val trackIds: Set<Long>) {
    val isEmpty: Boolean get() = endTick <= startTick
}

data class InputActivity(val deviceName: String, val note: Int, val atMillis: Long)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ProjectStore(File(app.filesDir, "project.json"))
    private val prefs = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    private val mapper = ControlMapper(ControlMapper.decode(prefs.getString(PREF_CONTROLS, null)))

    private val _controlBindings = MutableStateFlow(mapper.bindings())
    val controlBindings: StateFlow<Map<ControlAction, MidiTrigger>> = _controlBindings.asStateFlow()

    private val _learning = MutableStateFlow<ControlAction?>(null)
    /** Acción que está esperando a que pulses un botón del controlador. */
    val learning: StateFlow<ControlAction?> = _learning.asStateFlow()
    private val recorder = Recorder()
    private val devicesRepo = MidiDeviceRepository(app, ::onMidiEvent)
    private val bleScanner = BleMidiScanner(app)
    private val player = Player { resolveOutput() }
    private val metronome = Metronome()
    private val soundFontLibrary = SoundFontLibrary(app)
    private val synth = SynthOutput(null)

    private val _soundFonts = MutableStateFlow<List<String>>(emptyList())
    val soundFonts: StateFlow<List<String>> = _soundFonts.asStateFlow()

    private val _activeSoundFont = MutableStateFlow<String?>(null)
    /** SoundFont que usa el sintetizador interno, o null mientras carga. */
    val activeSoundFont: StateFlow<String?> = _activeSoundFont.asStateFlow()

    private val _project = MutableStateFlow(Project())
    val project: StateFlow<Project> = _project.asStateFlow()

    private val _transport = MutableStateFlow(TransportState.STOPPED)
    val transport: StateFlow<TransportState> = _transport.asStateFlow()

    /** Posición del cabezal de reproducción, en ticks. */
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    /** Cursor de edición: donde empieza la reproducción y la grabación. */
    private val _cursor = MutableStateFlow(0L)
    val cursor: StateFlow<Long> = _cursor.asStateFlow()

    private val _selection = MutableStateFlow<Selection?>(null)
    val selection: StateFlow<Selection?> = _selection.asStateFlow()

    private val _output = MutableStateFlow<OutputTarget>(OutputTarget.SameAsInput)
    val output: StateFlow<OutputTarget> = _output.asStateFlow()

    private val _inputActivity = MutableStateFlow<InputActivity?>(null)
    val inputActivity: StateFlow<InputActivity?> = _inputActivity.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private val _metronomeOn = MutableStateFlow(false)
    val metronomeOn: StateFlow<Boolean> = _metronomeOn.asStateFlow()

    private val undoStack = ArrayDeque<Project>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    val devices: StateFlow<List<MidiDeviceEntry>> = devicesRepo.devices
    val bleResults: StateFlow<List<BleMidiCandidate>> = bleScanner.results
    val bleScanning: StateFlow<Boolean> = bleScanner.scanning
    val recordingNotes: StateFlow<List<Note>> = recorder.liveNotes

    @Volatile private var lastInputDeviceId: Int? = null
    private var playStartTick = 0L
    private var playStartNanos = 0L
    private var tickerJob: Job? = null
    private var saveJob: Job? = null

    init {
        if (!devicesRepo.isMidiSupported) {
            _messages.tryEmit("Este teléfono no soporta MIDI")
        }
        devicesRepo.start()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                soundFontLibrary.ensureDefault()
                _soundFonts.value = soundFontLibrary.list()
                val wanted = prefs.getString(PREF_SOUNDFONT, null)?.takeIf { it in _soundFonts.value }
                loadSoundFont(wanted ?: SoundFontLibrary.DEFAULT_FILE)
            } catch (e: Exception) {
                _messages.tryEmit("No se pudo preparar el sintetizador interno")
            }
        }
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { store.load() }
            if (loaded != null && _project.value.tracks.isEmpty()) _project.value = loaded
        }
    }

    override fun onCleared() {
        stopEngines()
        synth.release()
        bleScanner.stop()
        devicesRepo.release()
        store.save(_project.value)
    }

    // ---------- Entrada MIDI ----------

    private fun onMidiEvent(deviceId: Int, event: MidiEvent, timestamp: Long) {
        when (val result = mapper.process(event)) {
            ControlMapper.Result.PassThrough -> Unit
            ControlMapper.Result.Consumed -> return
            is ControlMapper.Result.Triggered -> {
                viewModelScope.launch(Dispatchers.Main) { perform(result.action) }
                return
            }
            is ControlMapper.Result.Learned -> {
                viewModelScope.launch(Dispatchers.Main) {
                    _learning.value = null
                    saveBindings()
                    _messages.tryEmit("${result.action.label}: ${result.trigger.describe()}")
                }
                return
            }
        }
        recorder.onEvent(event, timestamp)
        // Si se escucha por el sintetizador, también suena lo que tocas en vivo.
        if (outputIsSynth()) synth.send(event.toBytes())
        if (event is MidiEvent.NoteOn) {
            lastInputDeviceId = deviceId
            _inputActivity.value = InputActivity(
                devicesRepo.nameOf(deviceId) ?: "MIDI",
                event.note,
                System.currentTimeMillis(),
            )
        }
    }

    // ---------- Salida ----------

    /** Dispositivo MIDI al que enviar, o null si toca usar el sintetizador interno. */
    private fun resolveDeviceId(): Int? = when (val target = _output.value) {
        is OutputTarget.Device -> target.id.takeIf { id -> devices.value.any { it.id == id && it.isOpen } }
        OutputTarget.SameAsInput -> {
            val playable = devices.value.filter { it.canPlay && it.isOpen }
            lastInputDeviceId?.takeIf { last -> playable.any { it.id == last } }
                ?: playable.firstOrNull()?.id
        }
        OutputTarget.InternalSynth -> null
    }

    private fun outputIsSynth(): Boolean = resolveDeviceId() == null && synth.soundFont != null

    private fun resolveOutput(): MidiOut? {
        val id = resolveDeviceId()
        if (id != null) return MidiOut { data -> devicesRepo.send(id, data) }
        // Sin dispositivo que pueda sonar: auriculares o altavoz del teléfono.
        return if (synth.soundFont != null) synth else null
    }

    // ---------- SoundFonts ----------

    private fun loadSoundFont(name: String) {
        val sf = SoundFont.load(soundFontLibrary.file(name))
        synth.soundFont = sf
        _activeSoundFont.value = name
        prefs.edit().putString(PREF_SOUNDFONT, name).apply()
    }

    fun selectSoundFont(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                loadSoundFont(name)
            } catch (e: Exception) {
                _messages.tryEmit("No se pudo cargar $name")
            }
        }
    }

    fun importSoundFont(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _messages.tryEmit("Cargando SoundFont…")
            try {
                val name = soundFontLibrary.import(uri)
                _soundFonts.value = soundFontLibrary.list()
                loadSoundFont(name)
                _messages.tryEmit("$name listo")
            } catch (e: Exception) {
                _messages.tryEmit(e.message ?: "No se pudo añadir el SoundFont")
            }
        }
    }

    fun deleteSoundFont(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_activeSoundFont.value == name) loadSoundFont(SoundFontLibrary.DEFAULT_FILE)
            soundFontLibrary.delete(name)
            _soundFonts.value = soundFontLibrary.list()
        }
    }

    fun setOutput(target: OutputTarget) {
        _output.value = target
    }

    private fun warnIfNoOutput() {
        if (resolveOutput() == null) {
            _messages.tryEmit("El sintetizador interno aún está cargando")
        }
    }

    // ---------- Transporte ----------

    fun playPause() {
        when (_transport.value) {
            TransportState.PLAYING, TransportState.RECORDING -> pause()
            TransportState.PAUSED -> startPlayback(_position.value)
            TransportState.STOPPED -> startPlayback(_selection.value?.startTick ?: _cursor.value)
        }
    }

    private fun startPlayback(fromTick: Long) {
        if (_project.value.tracks.isEmpty()) {
            _messages.tryEmit("Todavía no hay pistas. Pulsa grabar y toca algo.")
            return
        }
        warnIfNoOutput()
        beginTransport(fromTick, TransportState.PLAYING)
    }

    fun record() {
        when (_transport.value) {
            TransportState.RECORDING -> {
                stop()
                return
            }
            TransportState.PLAYING -> stopEngines()
            else -> Unit
        }
        val from = if (_transport.value == TransportState.STOPPED) {
            _selection.value?.startTick ?: _cursor.value
        } else {
            _position.value
        }
        if (devices.value.none { it.canRecord && it.isOpen }) {
            _messages.tryEmit("Conecta un dispositivo MIDI (USB o Bluetooth) para grabar")
        }
        beginTransport(from, TransportState.RECORDING)
    }

    private fun beginTransport(fromTick: Long, state: TransportState) {
        val now = System.nanoTime()
        playStartTick = fromTick
        playStartNanos = now
        _position.value = fromTick
        if (state == TransportState.RECORDING) {
            recorder.start(fromTick, now, _project.value.bpm)
        }
        player.start(_project.value, fromTick, now)
        if (_metronomeOn.value) metronome.start(fromTick, _project.value.bpm, _project.value.beatsPerBar)
        _transport.value = state
        startTicker()
    }

    fun pause() {
        val state = _transport.value
        if (state != TransportState.PLAYING && state != TransportState.RECORDING) return
        tickerJob?.cancel()
        _position.value = currentTick()
        stopEngines()
        if (state == TransportState.RECORDING) finishRecording()
        _transport.value = TransportState.PAUSED
    }

    fun stop() {
        tickerJob?.cancel()
        stopEngines()
        if (_transport.value == TransportState.RECORDING) finishRecording()
        _transport.value = TransportState.STOPPED
        _position.value = _cursor.value
    }

    fun goToStart() {
        val wasPlaying = _transport.value == TransportState.PLAYING
        if (_transport.value == TransportState.RECORDING) return
        if (wasPlaying) stopEngines()
        _cursor.value = 0
        _position.value = 0
        _selection.value = null
        if (wasPlaying) beginTransport(0, TransportState.PLAYING) else _transport.value = TransportState.STOPPED
    }

    private fun stopEngines() {
        player.stop()
        metronome.stop()
    }

    fun toggleMetronome() {
        val on = !_metronomeOn.value
        _metronomeOn.value = on
        val state = _transport.value
        if (state == TransportState.PLAYING || state == TransportState.RECORDING) {
            if (on) {
                metronome.start(currentTick(), _project.value.bpm, _project.value.beatsPerBar)
            } else {
                metronome.stop()
            }
        }
    }

    private fun currentTick(): Long =
        playStartTick + TimeMath.nanosToTicks(System.nanoTime() - playStartNanos, _project.value.bpm)

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                val tick = currentTick()
                _position.value = tick
                if (_transport.value == TransportState.PLAYING && tick > _project.value.endTick + PPQ) {
                    stop()
                    break
                }
                delay(16)
            }
        }
    }

    private fun finishRecording() {
        val result = recorder.stop(System.nanoTime()) ?: return
        updateProject { p ->
            val id = p.nextTrackId
            val track = Track(
                id = id,
                name = "Pista $id",
                channel = result.channel,
                program = result.program,
                notes = result.notes,
                controls = result.controls,
                colorIndex = ((id - 1) % TRACK_COLORS).toInt(),
            )
            p.copy(tracks = p.tracks + track, nextTrackId = id + 1)
        }
    }

    // ---------- Edición y pistas ----------

    fun setCursor(tick: Long) {
        val t = tick.coerceAtLeast(0)
        _selection.value = null
        _cursor.value = t
        when (_transport.value) {
            TransportState.STOPPED, TransportState.PAUSED -> {
                _position.value = t
                _transport.value = TransportState.STOPPED
            }
            TransportState.PLAYING -> {
                stopEngines()
                beginTransport(t, TransportState.PLAYING)
            }
            TransportState.RECORDING -> Unit
        }
    }

    fun setSelection(selection: Selection?) {
        _selection.value = selection
        if (selection != null && _transport.value != TransportState.RECORDING &&
            _transport.value != TransportState.PLAYING
        ) {
            _cursor.value = selection.startTick
            _position.value = selection.startTick
            _transport.value = TransportState.STOPPED
        }
    }

    /** Selecciona la pista entera, como al pulsar su cabecera en Audacity. */
    fun selectRow(trackIds: Set<Long>) {
        val end = maxOf(_project.value.endTick + 1, PPQ.toLong())
        val current = _selection.value
        _selection.value = if (current != null && current.trackIds == trackIds && current.startTick == 0L) {
            null
        } else {
            Selection(0, end, trackIds)
        }
    }

    // ---------- Cuantizar, borrar, agrupar ----------

    fun quantizeSelection(grid: QuantizeGrid, quantizeEnds: Boolean) {
        val sel = _selection.value ?: return
        quantize(sel.trackIds, sel.startTick, sel.endTick, grid, quantizeEnds)
    }

    fun quantizeTrack(trackId: Long, grid: QuantizeGrid, quantizeEnds: Boolean) =
        quantize(setOf(trackId), 0, Long.MAX_VALUE, grid, quantizeEnds)

    private fun quantize(ids: Set<Long>, start: Long, end: Long, grid: QuantizeGrid, ends: Boolean) {
        updateProject { p ->
            p.copy(tracks = p.tracks.map { t ->
                if (t.id in ids) t.copy(notes = Quantizer.quantize(t.notes, grid.ticks, start, end, 1f, ends)) else t
            })
        }
        _messages.tryEmit("Cuantizado a ${grid.label}")
    }

    fun deleteSelectedNotes() {
        val sel = _selection.value ?: return
        updateProject { p ->
            p.copy(tracks = p.tracks.map { t ->
                if (t.id in sel.trackIds) {
                    t.copy(
                        notes = t.notes.filterNot { it.startTick >= sel.startTick && it.startTick < sel.endTick },
                        controls = t.controls.filterNot { it.tick >= sel.startTick && it.tick < sel.endTick },
                    )
                } else {
                    t
                }
            })
        }
    }

    fun groupSelected() {
        val sel = _selection.value ?: return
        if (sel.trackIds.size < 2) {
            _messages.tryEmit("Selecciona dos o más pistas para agruparlas")
            return
        }
        updateProject { p ->
            val id = p.nextGroupId
            val names = p.tracks.filter { it.id in sel.trackIds }.joinToString(" + ") { it.name }
            cleanGroups(
                p.copy(
                    tracks = p.tracks.map { if (it.id in sel.trackIds) it.copy(groupId = id) else it },
                    groups = p.groups + TrackGroup(id, names),
                    nextGroupId = id + 1,
                ),
            )
        }
        _selection.value = null
    }

    fun ungroup(groupId: Long) {
        updateProject { p ->
            cleanGroups(p.copy(tracks = p.tracks.map { if (it.groupId == groupId) it.copy(groupId = null) else it }))
        }
    }

    /** Quita grupos vacíos o de una sola pista. */
    private fun cleanGroups(p: Project): Project {
        val counts = p.tracks.mapNotNull { it.groupId }.groupingBy { it }.eachCount()
        val alive = counts.filterValues { it >= 2 }.keys
        return p.copy(
            groups = p.groups.filter { it.id in alive },
            tracks = p.tracks.map { if (it.groupId != null && it.groupId !in alive) it.copy(groupId = null) else it },
        )
    }

    /** Mute o Solo de un grupo entero: si todas lo tienen, se quita; si no, se pone. */
    fun toggleMuteAll(ids: Set<Long>) = updateProject { p ->
        val allOn = p.tracks.filter { it.id in ids }.all { it.muted }
        p.copy(tracks = p.tracks.map { if (it.id in ids) it.copy(muted = !allOn) else it })
    }

    fun toggleSoloAll(ids: Set<Long>) = updateProject { p ->
        val allOn = p.tracks.filter { it.id in ids }.all { it.solo }
        p.copy(tracks = p.tracks.map { if (it.id in ids) it.copy(solo = !allOn) else it })
    }

    fun undo() {
        if (_transport.value == TransportState.RECORDING) return
        val previous = undoStack.removeLastOrNull() ?: return
        _canUndo.value = undoStack.isNotEmpty()
        _project.value = previous
        player.updateAudible(previous)
        _selection.value = null
        scheduleSave()
    }


    fun renameTrack(trackId: Long, name: String) = updateTrack(trackId) { it.copy(name = name.ifBlank { it.name }) }

    fun setProgram(trackId: Long, program: Int?) {
        updateTrack(trackId) { it.copy(program = program) }
        val track = _project.value.tracks.firstOrNull { it.id == trackId } ?: return
        if (program != null) {
            resolveOutput()?.send(com.midirecording.app.midi.MidiBytes.programChange(track.channel, program))
        }
    }

    fun setChannel(trackId: Long, channel: Int) = updateTrack(trackId) { it.copy(channel = channel.coerceIn(0, 15)) }

    fun deleteTrack(trackId: Long) {
        updateProject { p -> cleanGroups(p.copy(tracks = p.tracks.filterNot { it.id == trackId })) }
        _selection.value = _selection.value?.let { s ->
            s.copy(trackIds = s.trackIds - trackId).takeIf { it.trackIds.isNotEmpty() }
        }
    }

    fun changeBpm(delta: Double) = setBpm(_project.value.bpm + delta)

    fun setBpm(bpm: Double) {
        when (_transport.value) {
            TransportState.RECORDING -> {
                _messages.tryEmit("No se puede cambiar el BPM mientras se graba")
            }
            TransportState.PLAYING -> {
                // Sigue sonando desde el mismo punto con el tempo nuevo.
                val at = currentTick()
                stopEngines()
                updateProject { it.copy(bpm = bpm.coerceIn(20.0, 300.0)) }
                beginTransport(at, TransportState.PLAYING)
            }
            else -> updateProject { it.copy(bpm = bpm.coerceIn(20.0, 300.0)) }
        }
    }

    // ---------- Botones del controlador (modo aprender) ----------

    fun learn(action: ControlAction?) {
        mapper.startLearning(action)
        _learning.value = action
    }

    fun clearBinding(action: ControlAction) {
        mapper.clear(action)
        saveBindings()
    }

    private fun saveBindings() {
        val b = mapper.bindings()
        _controlBindings.value = b
        prefs.edit().putString(PREF_CONTROLS, ControlMapper.encode(b)).apply()
    }

    private fun perform(action: ControlAction) {
        when (action) {
            ControlAction.RECORD -> record()
            ControlAction.PLAY_PAUSE -> playPause()
            ControlAction.STOP -> stop()
            ControlAction.GO_TO_START -> goToStart()
            ControlAction.METRONOME -> toggleMetronome()
            ControlAction.BPM_UP -> changeBpm(1.0)
            ControlAction.BPM_DOWN -> changeBpm(-1.0)
            ControlAction.UNDO -> undo()
            ControlAction.MUTE, ControlAction.SOLO -> {
                val ids = _selection.value?.trackIds
                if (ids.isNullOrEmpty()) {
                    _messages.tryEmit("Selecciona una pista para usar Mute o Solo desde el controlador")
                } else if (action == ControlAction.MUTE) {
                    toggleMuteAll(ids)
                } else {
                    toggleSoloAll(ids)
                }
            }
        }
    }

    private fun updateTrack(trackId: Long, change: (Track) -> Track) = updateProject { p ->
        p.copy(tracks = p.tracks.map { if (it.id == trackId) change(it) else it })
    }

    private fun updateProject(change: (Project) -> Project) {
        val before = _project.value
        val updated = change(before)
        if (updated == before) return
        undoStack.addLast(before)
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        _canUndo.value = true
        _project.value = updated
        player.updateAudible(updated)
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(600)
            store.save(_project.value)
        }
    }

    // ---------- Dispositivos ----------

    fun refreshDevices() = devicesRepo.refresh()

    fun startBleScan() {
        if (!bleScanner.start()) {
            _messages.tryEmit("Activa el Bluetooth para buscar dispositivos MIDI")
        }
    }

    fun stopBleScan() = bleScanner.stop()

    fun connectBle(candidate: BleMidiCandidate) {
        bleScanner.stop()
        devicesRepo.connectBluetooth(candidate.device)
        _messages.tryEmit("Conectando con ${candidate.name}…")
    }

    companion object {
        const val TRACK_COLORS = 8
        const val MAX_UNDO = 50
        const val PREF_CONTROLS = "controls"
        const val PREF_SOUNDFONT = "soundfont"
    }
}
