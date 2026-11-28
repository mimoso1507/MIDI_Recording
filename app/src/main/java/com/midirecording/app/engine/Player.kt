package com.midirecording.app.engine

import com.midirecording.app.midi.MidiBytes
import com.midirecording.app.model.Project
import com.midirecording.app.model.TimeMath
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.LockSupport
import kotlin.math.min

/** Destino de la reproducción: un dispositivo MIDI o, más adelante, el sintetizador interno. */
fun interface MidiOut {
    fun send(data: ByteArray)
}

/**
 * Reproduce el proyecto enviando los eventos al [MidiOut] actual desde un
 * hilo de alta prioridad. Mute y Solo se pueden cambiar mientras suena.
 */
class Player(private val output: () -> MidiOut?) {

    data class ScheduledEvent(
        val tick: Long,
        /** Orden dentro del mismo tick: program change, note off, controles, note on. */
        val order: Int,
        val trackId: Long,
        val kind: Kind,
        val channel: Int,
        val bytes: ByteArray,
    )

    enum class Kind { PROGRAM, NOTE_OFF, CONTROL, NOTE_ON }

    @Volatile private var thread: Thread? = null
    @Volatile private var audible: Set<Long> = emptySet()
    private val sounding = ConcurrentHashMap.newKeySet<Int>()
    private val usedChannels = ConcurrentHashMap.newKeySet<Int>()

    val isPlaying: Boolean get() = thread != null

    fun start(project: Project, fromTick: Long, startNanos: Long) {
        stop()
        val events = buildEvents(project, fromTick)
        audible = project.audibleTrackIds()
        project.tracks.forEach { usedChannels += it.channel }
        val nanosPerTick = TimeMath.nanosPerTick(project.bpm)
        val t = Thread({ run(events, fromTick, startNanos, nanosPerTick) }, "midi-player")
        t.priority = Thread.MAX_PRIORITY
        thread = t
        t.start()
    }

    fun updateAudible(project: Project) {
        audible = project.audibleTrackIds()
    }

    fun stop() {
        val t = thread ?: return
        thread = null
        t.interrupt()
        try {
            t.join(300)
        } catch (_: InterruptedException) {
        }
        silence()
    }

    private fun run(events: List<ScheduledEvent>, fromTick: Long, startNanos: Long, nanosPerTick: Double) {
        var i = 0
        val me = Thread.currentThread()
        while (!me.isInterrupted && i < events.size) {
            val e = events[i]
            val target = startNanos + ((e.tick - fromTick) * nanosPerTick).toLong()
            val wait = target - System.nanoTime()
            if (wait > 3_000_000L) {
                try {
                    Thread.sleep(min((wait - 2_000_000L) / 1_000_000L, 20L))
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }
            if (wait > 0) LockSupport.parkNanos(wait)
            if (me.isInterrupted) break
            dispatch(e)
            i++
        }
        if (thread === me) thread = null
    }

    private fun dispatch(e: ScheduledEvent) {
        val out = output() ?: return
        when (e.kind) {
            Kind.NOTE_ON -> {
                if (e.trackId !in audible) return
                sounding += noteKey(e.channel, e.bytes[1].toInt())
                out.send(e.bytes)
            }
            Kind.NOTE_OFF -> {
                if (sounding.remove(noteKey(e.channel, e.bytes[1].toInt()))) out.send(e.bytes)
            }
            Kind.CONTROL -> if (e.trackId in audible) out.send(e.bytes)
            Kind.PROGRAM -> out.send(e.bytes)
        }
    }

    /** Apaga todas las notas que quedaron sonando y suelta el pedal. */
    private fun silence() {
        val out = output() ?: run {
            sounding.clear()
            return
        }
        sounding.forEach { key -> out.send(MidiBytes.noteOff(key / 128, key % 128)) }
        sounding.clear()
        usedChannels.forEach { ch ->
            out.send(MidiBytes.controlChange(ch, MidiBytes.CC_SUSTAIN, 0))
            out.send(MidiBytes.controlChange(ch, MidiBytes.CC_ALL_NOTES_OFF, 0))
        }
    }

    private fun noteKey(channel: Int, note: Int) = channel * 128 + note

    companion object {
        fun buildEvents(project: Project, fromTick: Long): List<ScheduledEvent> {
            val list = ArrayList<ScheduledEvent>()
            for (track in project.tracks) {
                val ch = track.channel
                track.program?.let {
                    list += ScheduledEvent(fromTick, 0, track.id, Kind.PROGRAM, ch, MidiBytes.programChange(ch, it))
                }
                for (n in track.notes) {
                    if (n.startTick < fromTick) continue
                    list += ScheduledEvent(n.startTick, 3, track.id, Kind.NOTE_ON, ch, MidiBytes.noteOn(ch, n.pitch, n.velocity))
                    list += ScheduledEvent(n.endTick, 1, track.id, Kind.NOTE_OFF, ch, MidiBytes.noteOff(ch, n.pitch))
                }
                for (c in track.controls) {
                    if (c.tick < fromTick) continue
                    list += ScheduledEvent(
                        c.tick, 2, track.id, Kind.CONTROL, ch,
                        MidiBytes.channelMessage(c.status, ch, c.data1, c.data2),
                    )
                }
            }
            list.sortWith(compareBy<ScheduledEvent>({ it.tick }, { it.order }))
            return list
        }
    }
}
