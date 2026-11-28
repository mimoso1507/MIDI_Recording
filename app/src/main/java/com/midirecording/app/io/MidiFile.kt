package com.midirecording.app.io

import com.midirecording.app.model.ControlEvent
import com.midirecording.app.model.Note
import com.midirecording.app.model.PPQ
import com.midirecording.app.model.Project
import com.midirecording.app.model.Track
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Lectura y escritura de archivos MIDI estándar (.mid, formatos 0 y 1). */
object MidiFile {

    // ---------- Exportar ----------

    fun write(project: Project): ByteArray {
        val out = ByteArrayOutputStream()
        val tracks = project.tracks.filter { it.notes.isNotEmpty() || it.controls.isNotEmpty() }
        out.write("MThd".toByteArray())
        out.write32(6)
        out.write16(1)
        out.write16(tracks.size + 1)
        out.write16(PPQ)

        // Pista de tempo
        val tempo = ByteArrayOutputStream()
        val usPerBeat = (60_000_000.0 / project.bpm).roundToInt()
        tempo.writeVar(0); tempo.write(byteArrayOf(0xFF.toByte(), 0x51, 3))
        tempo.write(usPerBeat shr 16 and 0xFF); tempo.write(usPerBeat shr 8 and 0xFF); tempo.write(usPerBeat and 0xFF)
        tempo.writeVar(0); tempo.write(byteArrayOf(0xFF.toByte(), 0x58, 4))
        tempo.write(project.beatsPerBar); tempo.write(2); tempo.write(24); tempo.write(8)
        tempo.writeVar(0); tempo.write(byteArrayOf(0xFF.toByte(), 0x2F, 0))
        out.writeChunk(tempo.toByteArray())

        for (t in tracks) {
            val ch = t.channel and 0x0F
            data class Ev(val tick: Long, val order: Int, val bytes: ByteArray)
            val events = ArrayList<Ev>()
            t.program?.let { events += Ev(0, 0, byteArrayOf((0xC0 or ch).toByte(), it.toByte())) }
            t.notes.forEach { n ->
                events += Ev(n.startTick, 2, byteArrayOf((0x90 or ch).toByte(), n.pitch.toByte(), n.velocity.coerceIn(1, 127).toByte()))
                events += Ev(n.endTick, 1, byteArrayOf((0x80 or ch).toByte(), n.pitch.toByte(), 64))
            }
            t.controls.forEach { c ->
                val s = (c.status or ch).toByte()
                events += Ev(
                    c.tick, 1,
                    if (c.status == 0xD0 || c.status == 0xC0) byteArrayOf(s, c.data1.toByte())
                    else byteArrayOf(s, c.data1.toByte(), c.data2.toByte()),
                )
            }
            events.sortWith(compareBy<Ev>({ it.tick }, { it.order }))
            val body = ByteArrayOutputStream()
            val name = t.name.toByteArray(Charsets.UTF_8)
            body.writeVar(0); body.write(byteArrayOf(0xFF.toByte(), 0x03)); body.writeVar(name.size); body.write(name)
            var last = 0L
            for (e in events) {
                body.writeVar(e.tick - last)
                body.write(e.bytes)
                last = e.tick
            }
            body.writeVar(0); body.write(byteArrayOf(0xFF.toByte(), 0x2F, 0))
            out.writeChunk(body.toByteArray())
        }
        return out.toByteArray()
    }

    // ---------- Importar ----------

    /** Convierte el archivo en pistas: una por cada pista y canal del archivo. */
    fun read(data: ByteArray, firstTrackId: Long = 1): Project {
        val r = Reader(data)
        if (r.fourcc() != "MThd") throw IOException("No es un archivo MIDI")
        val headerLen = r.u32()
        val format = r.u16()
        val ntrks = r.u16()
        val division = r.u16()
        r.skip(headerLen - 6)
        if (division and 0x8000 != 0) throw IOException("Archivos MIDI con tiempo SMPTE no soportados")
        val scale = PPQ.toDouble() / division
        var bpm: Double? = null
        var beatsPerBar = 4

        class Building(val name: String?, val channel: Int) {
            val notes = ArrayList<Note>()
            val controls = ArrayList<ControlEvent>()
            var program: Int? = null
        }
        val result = ArrayList<Building>()

        for (trackIndex in 0 until ntrks) {
            if (r.remaining() < 8) break
            val id = r.fourcc()
            val len = r.u32()
            val end = r.pos + len
            if (id != "MTrk") {
                r.pos = end
                continue
            }
            var tick = 0L
            var status = 0
            var trackName: String? = null
            val byChannel = LinkedHashMap<Int, Building>()
            val open = HashMap<Int, ArrayDeque<Pair<Long, Int>>>()
            fun ch(c: Int) = byChannel.getOrPut(c) { Building(null, c) }
            while (r.pos < end) {
                tick += r.varLen()
                var b = r.u8()
                if (b == 0xFF) {
                    val type = r.u8()
                    val l = r.varLen().toInt()
                    val start = r.pos
                    when (type) {
                        0x51 -> if (bpm == null && l >= 3) {
                            val us = (r.u8() shl 16) or (r.u8() shl 8) or r.u8()
                            if (us > 0) bpm = 60_000_000.0 / us
                        }
                        0x58 -> if (l >= 1) beatsPerBar = r.u8().coerceIn(1, 16)
                        0x03 -> trackName = String(data, start, l, Charsets.UTF_8).trim().ifEmpty { null }
                        0x2F -> Unit
                    }
                    r.pos = start + l
                    continue
                }
                if (b == 0xF0 || b == 0xF7) {
                    r.pos += r.varLen().toInt()
                    continue
                }
                val d1: Int
                if (b and 0x80 == 0) {
                    d1 = b
                    b = status
                } else {
                    status = b
                    d1 = if (needsData(b)) r.u8() else 0
                }
                if (b and 0x80 == 0) continue
                val type = b and 0xF0
                val c = b and 0x0F
                val d2 = if (type == 0xC0 || type == 0xD0) 0 else r.u8()
                val t = (tick * scale).roundToLong()
                when (type) {
                    0x90, 0x80 -> {
                        val key = c * 128 + d1
                        if (type == 0x90 && d2 > 0) {
                            open.getOrPut(key) { ArrayDeque() }.addLast(t to d2)
                        } else {
                            open[key]?.removeFirstOrNull()?.let { (s, v) ->
                                ch(c).notes += Note(d1, v, s, maxOf(1L, t - s))
                            }
                        }
                    }
                    0xB0 -> ch(c).controls += ControlEvent(t, 0xB0, d1, d2)
                    0xE0 -> ch(c).controls += ControlEvent(t, 0xE0, d1, d2)
                    0xD0 -> ch(c).controls += ControlEvent(t, 0xD0, d1, 0)
                    0xC0 -> if (ch(c).program == null) ch(c).program = d1
                }
            }
            r.pos = end
            // Notas sin note off: se cierran en el último evento de la pista
            val lastTick = (tick * scale).roundToLong()
            open.forEach { (key, q) ->
                q.forEach { (s, v) -> ch(key / 128).notes += Note(key % 128, v, s, maxOf(1L, lastTick - s)) }
            }
            byChannel.values.filter { it.notes.isNotEmpty() }.forEach { bld ->
                val name = trackName ?: if (bld.channel == 9) "Batería" else "Pista ${trackIndex + 1}"
                val nb = Building(
                    if (byChannel.values.count { it.notes.isNotEmpty() } > 1) "$name (canal ${bld.channel + 1})" else name,
                    bld.channel,
                )
                nb.notes += bld.notes.sortedBy { it.startTick }
                nb.controls += bld.controls
                nb.program = bld.program
                result += nb
            }
        }
        @Suppress("UNUSED_VARIABLE") val unused = format
        var id = firstTrackId
        val tracks = result.map { b ->
            val tid = id++
            Track(
                id = tid,
                name = b.name ?: "Pista $tid",
                channel = b.channel,
                program = b.program,
                notes = b.notes,
                controls = b.controls.sortedBy { it.tick },
                colorIndex = ((tid - 1) % 8).toInt(),
            )
        }
        return Project(
            bpm = (bpm ?: 120.0).let { (it * 10).roundToLong() / 10.0 },
            beatsPerBar = beatsPerBar,
            tracks = tracks,
            nextTrackId = id,
        )
    }

    private fun needsData(status: Int) = status < 0xF0

    private class Reader(val data: ByteArray) {
        var pos = 0
        fun remaining() = data.size - pos
        fun u8(): Int {
            if (pos >= data.size) throw IOException("Archivo MIDI incompleto")
            return data[pos++].toInt() and 0xFF
        }
        fun u16() = (u8() shl 8) or u8()
        fun u32() = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
        fun fourcc() = String(CharArray(4) { u8().toChar() })
        fun skip(n: Int) { pos += maxOf(0, n) }
        fun varLen(): Long {
            var v = 0L
            repeat(4) {
                val b = u8()
                v = (v shl 7) or (b and 0x7F).toLong()
                if (b and 0x80 == 0) return v
            }
            return v
        }
    }

    private fun ByteArrayOutputStream.write16(v: Int) { write(v shr 8 and 0xFF); write(v and 0xFF) }
    private fun ByteArrayOutputStream.write32(v: Int) { write16(v shr 16); write16(v and 0xFFFF) }
    private fun ByteArrayOutputStream.writeVar(value: Long) {
        var v = value
        val buf = IntArray(5)
        var n = 0
        buf[n++] = (v and 0x7F).toInt()
        v = v shr 7
        while (v > 0) {
            buf[n++] = ((v and 0x7F) or 0x80).toInt()
            v = v shr 7
        }
        for (i in n - 1 downTo 0) write(buf[i])
    }
    private fun ByteArrayOutputStream.writeVar(value: Int) = writeVar(value.toLong())
    private fun ByteArrayOutputStream.writeChunk(body: ByteArray) {
        write("MTrk".toByteArray())
        write32(body.size)
        write(body)
    }
}
