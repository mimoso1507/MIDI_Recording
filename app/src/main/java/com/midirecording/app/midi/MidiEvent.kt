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
