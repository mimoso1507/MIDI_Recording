package com.midirecording.app.midi

/**
 * Convierte un flujo de bytes MIDI en [MidiEvent]. Soporta running status,
 * varios mensajes por paquete y mensajes partidos entre paquetes. Ignora
 * SysEx, mensajes comunes de sistema y de tiempo real.
 *
 * Hay que usar un parser por cada puerto de entrada, porque el running status
 * es propio de cada flujo.
 */
class MidiParser(private val onEvent: (MidiEvent, Long) -> Unit) {
    private var status = 0
    private var data1 = 0
    private var count = 0
    private var inSysex = false

    fun feed(bytes: ByteArray, offset: Int, length: Int, timestamp: Long) {
        for (i in offset until offset + length) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b >= 0xF8 -> Unit // tiempo real: no rompe el running status
                b == 0xF0 -> {
                    inSysex = true
                    status = 0
                }
                b == 0xF7 -> inSysex = false
                b >= 0xF1 -> {
                    inSysex = false
                    status = 0
                }
                (b and 0x80) != 0 -> {
                    inSysex = false
                    status = b
                    count = 0
                }
                inSysex || status == 0 -> Unit
                count == 0 -> {
                    data1 = b
                    if (dataLength(status) == 1) {
                        emit(status, data1, 0, timestamp)
                    } else {
                        count = 1
                    }
                }
                else -> {
                    emit(status, data1, b, timestamp)
                    count = 0
                }
            }
        }
    }

    private fun dataLength(s: Int) = when (s and 0xF0) {
        0xC0, 0xD0 -> 1
        else -> 2
    }

    private fun emit(s: Int, d1: Int, d2: Int, timestamp: Long) {
        val ch = s and 0x0F
        val event = when (s and 0xF0) {
            0x80 -> MidiEvent.NoteOff(ch, d1, d2)
            0x90 -> if (d2 == 0) MidiEvent.NoteOff(ch, d1, 64) else MidiEvent.NoteOn(ch, d1, d2)
            0xA0 -> MidiEvent.PolyPressure(ch, d1, d2)
            0xB0 -> MidiEvent.ControlChange(ch, d1, d2)
            0xC0 -> MidiEvent.ProgramChange(ch, d1)
            0xD0 -> MidiEvent.ChannelPressure(ch, d1)
            0xE0 -> MidiEvent.PitchBend(ch, d1 or (d2 shl 7))
            else -> null
        }
        if (event != null) onEvent(event, timestamp)
    }
}
