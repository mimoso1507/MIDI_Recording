package com.midirecording.app.engine

import com.midirecording.app.midi.MidiEvent
import com.midirecording.app.model.ControlEvent
import com.midirecording.app.model.Note
import com.midirecording.app.model.TimeMath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

data class RecordingResult(
    val notes: List<Note>,
    val controls: List<ControlEvent>,
    val channel: Int,
    val program: Int?,
)

/**
 * Convierte los eventos MIDI entrantes en notas con posición en ticks.
 * Es seguro llamarlo desde el hilo de MIDI y desde la UI a la vez.
 */
class Recorder {
    private val lock = Any()
    private var active = false
    private var startTick = 0L
    private var startNanos = 0L
    private var bpm = 120.0

    private val held = HashMap<Int, Pair<Long, Int>>() // canal*128+nota -> (tick, velocidad)
    private val notes = ArrayList<Note>()
    private val controls = ArrayList<ControlEvent>()
    private var channel: Int? = null
    private var program: Int? = null

    private val _liveNotes = MutableStateFlow<List<Note>>(emptyList())
    /** Notas ya terminadas de la toma en curso, para dibujarlas mientras se graba. */
    val liveNotes: StateFlow<List<Note>> = _liveNotes.asStateFlow()

    val isActive: Boolean get() = synchronized(lock) { active }

    fun start(fromTick: Long, nanos: Long, bpm: Double) = synchronized(lock) {
        active = true
        startTick = fromTick
        startNanos = nanos
        this.bpm = bpm
        held.clear()
        notes.clear()
        controls.clear()
        channel = null
        program = null
        _liveNotes.value = emptyList()
    }

    private fun tickAt(nanos: Long): Long =
        startTick + max(0L, TimeMath.nanosToTicks(nanos - startNanos, bpm))

    fun onEvent(event: MidiEvent, timestampNanos: Long) = synchronized(lock) {
        if (!active) return@synchronized
        val tick = tickAt(timestampNanos)
        when (event) {
            is MidiEvent.NoteOn -> {
                if (channel == null) channel = event.channel
                held[key(event.channel, event.note)] = tick to event.velocity
            }
            is MidiEvent.NoteOff -> {
                held.remove(key(event.channel, event.note))?.let { (start, velocity) ->
                    notes += Note(event.note, velocity, start, max(1L, tick - start))
                    _liveNotes.value = notes.toList()
                }
            }
            is MidiEvent.ControlChange ->
                controls += ControlEvent(tick, 0xB0, event.controller, event.value)
            is MidiEvent.PitchBend ->
                controls += ControlEvent(tick, 0xE0, event.value and 0x7F, (event.value shr 7) and 0x7F)
            is MidiEvent.ChannelPressure ->
                controls += ControlEvent(tick, 0xD0, event.pressure, 0)
            is MidiEvent.ProgramChange -> {
                if (channel == null) channel = event.channel
                program = event.program
            }
            is MidiEvent.PolyPressure -> Unit
        }
    }

    /** Termina la toma. Devuelve null si no se tocó nada. */
    fun stop(nowNanos: Long): RecordingResult? = synchronized(lock) {
        if (!active) return@synchronized null
        active = false
        val endTick = tickAt(nowNanos)
        held.forEach { (k, value) ->
            notes += Note(k % 128, value.second, value.first, max(1L, endTick - value.first))
        }
        held.clear()
        _liveNotes.value = emptyList()
        if (notes.isEmpty() && controls.isEmpty()) return@synchronized null
        RecordingResult(
            notes = notes.sortedBy { it.startTick },
            controls = controls.sortedBy { it.tick },
            channel = channel ?: 0,
            program = program,
        )
    }

    private fun key(channel: Int, note: Int) = channel * 128 + note
}
