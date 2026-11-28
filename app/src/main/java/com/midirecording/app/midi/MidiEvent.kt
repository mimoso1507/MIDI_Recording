package com.midirecording.app.midi

/** Mensajes MIDI de canal ya decodificados. Los canales van de 0 a 15. */
sealed interface MidiEvent {
    val channel: Int

    data class NoteOn(override val channel: Int, val note: Int, val velocity: Int) : MidiEvent
    data class NoteOff(override val channel: Int, val note: Int, val velocity: Int) : MidiEvent
    data class ControlChange(override val channel: Int, val controller: Int, val value: Int) : MidiEvent
    data class ProgramChange(override val channel: Int, val program: Int) : MidiEvent
    /** value de 0 a 16383, 8192 es el centro. */
    data class PitchBend(override val channel: Int, val value: Int) : MidiEvent
    data class ChannelPressure(override val channel: Int, val pressure: Int) : MidiEvent
    data class PolyPressure(override val channel: Int, val note: Int, val pressure: Int) : MidiEvent
}

/** Vuelve a convertir el evento en bytes MIDI (para reenviarlo al sintetizador). */
fun MidiEvent.toBytes(): ByteArray = when (this) {
    is MidiEvent.NoteOn -> MidiBytes.noteOn(channel, note, velocity)
    is MidiEvent.NoteOff -> MidiBytes.noteOff(channel, note)
    is MidiEvent.ControlChange -> MidiBytes.controlChange(channel, controller, value)
    is MidiEvent.ProgramChange -> MidiBytes.programChange(channel, program)
    is MidiEvent.PitchBend -> MidiBytes.channelMessage(0xE0, channel, value and 0x7F, (value shr 7) and 0x7F)
    is MidiEvent.ChannelPressure -> MidiBytes.channelMessage(0xD0, channel, pressure, 0)
    is MidiEvent.PolyPressure -> MidiBytes.channelMessage(0xA0, channel, note, pressure)
}

object MidiBytes {
    fun noteOn(channel: Int, note: Int, velocity: Int) =
        byteArrayOf((0x90 or channel).toByte(), note.toByte(), velocity.toByte())

    fun noteOff(channel: Int, note: Int) =
        byteArrayOf((0x80 or channel).toByte(), note.toByte(), 0)

    fun controlChange(channel: Int, controller: Int, value: Int) =
        byteArrayOf((0xB0 or channel).toByte(), controller.toByte(), value.toByte())

    fun programChange(channel: Int, program: Int) =
        byteArrayOf((0xC0 or channel).toByte(), program.toByte())

    /** Mensaje genérico de canal. status sin canal (0x80..0xE0). */
    fun channelMessage(status: Int, channel: Int, data1: Int, data2: Int): ByteArray {
        val s = (status and 0xF0) or (channel and 0x0F)
        return if (status == 0xC0 || status == 0xD0) {
            byteArrayOf(s.toByte(), data1.toByte())
        } else {
            byteArrayOf(s.toByte(), data1.toByte(), data2.toByte())
        }
    }

    const val CC_SUSTAIN = 64
    const val CC_ALL_NOTES_OFF = 123
}
