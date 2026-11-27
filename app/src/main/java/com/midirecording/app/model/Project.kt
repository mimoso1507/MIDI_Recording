package com.midirecording.app.model

import kotlin.math.max

/** Resolución interna: ticks por negra (igual que en los archivos .mid). */
const val PPQ = 480

data class Note(
    val pitch: Int,
    val velocity: Int,
    val startTick: Long,
    val durationTicks: Long,
) {
    val endTick: Long get() = startTick + durationTicks
}

/**
 * Eventos de canal que no son notas: control change (pedal, modulación...),
 * pitch bend y aftertouch. [status] va sin canal (0xB0, 0xE0, 0xD0, 0xA0).
 */
data class ControlEvent(
    val tick: Long,
    val status: Int,
    val data1: Int,
    val data2: Int,
)

data class Track(
    val id: Long,
    val name: String,
    val channel: Int = 0,
    /** Instrumento General MIDI (0-127) que se envía al reproducir, o null. */
    val program: Int? = null,
    val notes: List<Note> = emptyList(),
    val controls: List<ControlEvent> = emptyList(),
    val muted: Boolean = false,
    val solo: Boolean = false,
    val colorIndex: Int = 0,
) {
    val endTick: Long
        get() = max(notes.maxOfOrNull { it.endTick } ?: 0L, controls.maxOfOrNull { it.tick } ?: 0L)
}

data class Project(
    val bpm: Double = 120.0,
    val beatsPerBar: Int = 4,
    val tracks: List<Track> = emptyList(),
    val nextTrackId: Long = 1,
) {
    val endTick: Long get() = tracks.maxOfOrNull { it.endTick } ?: 0L
    val ticksPerBar: Long get() = PPQ.toLong() * beatsPerBar

    /** Pistas que deben sonar teniendo en cuenta Solo y Mute. */
    fun audibleTrackIds(): Set<Long> {
        val anySolo = tracks.any { it.solo }
        return tracks.filter { if (anySolo) it.solo else !it.muted }.map { it.id }.toSet()
    }
}

object TimeMath {
    fun nanosPerTick(bpm: Double): Double = 60_000_000_000.0 / (bpm * PPQ)

    fun ticksToNanos(ticks: Long, bpm: Double): Long = (ticks * nanosPerTick(bpm)).toLong()

    fun nanosToTicks(nanos: Long, bpm: Double): Long = (nanos / nanosPerTick(bpm)).toLong()

    fun ticksToMillis(ticks: Long, bpm: Double): Long = ticksToNanos(ticks, bpm) / 1_000_000

    /** "mm:ss.d" */
    fun formatTime(ticks: Long, bpm: Double): String {
        val ms = ticksToMillis(ticks, bpm)
        val minutes = ms / 60_000
        val seconds = (ms / 1000) % 60
        val tenths = (ms / 100) % 10
        return "%02d:%02d.%d".format(minutes, seconds, tenths)
    }

    /** "compás.tiempo", empezando en 1.1 */
    fun formatBarBeat(ticks: Long, beatsPerBar: Int): String {
        val beat = ticks / PPQ
        return "${beat / beatsPerBar + 1}.${beat % beatsPerBar + 1}"
    }
}
