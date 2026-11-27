package com.midirecording.app

import com.midirecording.app.midi.MidiEvent
import com.midirecording.app.midi.MidiParser
import org.junit.Assert.assertEquals
import org.junit.Test

class MidiParserTest {
    private fun parse(vararg chunks: IntArray): List<MidiEvent> {
        val out = mutableListOf<MidiEvent>()
        val parser = MidiParser { e, _ -> out += e }
        chunks.forEach { c ->
            val bytes = ByteArray(c.size) { c[it].toByte() }
            parser.feed(bytes, 0, bytes.size, 0L)
        }
        return out
    }

    @Test
    fun noteOnAndOff() {
        val events = parse(intArrayOf(0x90, 60, 100, 0x80, 60, 0))
        assertEquals(listOf(MidiEvent.NoteOn(0, 60, 100), MidiEvent.NoteOff(0, 60, 0)), events)
    }

    @Test
    fun runningStatusAndVelocityZero() {
        val events = parse(intArrayOf(0x91, 60, 90, 62, 80, 60, 0))
        assertEquals(
            listOf(MidiEvent.NoteOn(1, 60, 90), MidiEvent.NoteOn(1, 62, 80), MidiEvent.NoteOff(1, 60, 64)),
            events,
        )
    }

    @Test
    fun messageSplitAcrossPacketsWithRealtimeInside() {
        val events = parse(intArrayOf(0x90, 64), intArrayOf(0xF8, 70))
        assertEquals(listOf(MidiEvent.NoteOn(0, 64, 70)), events)
    }

    @Test
    fun sysexIgnoredAndOneByteMessages() {
        val events = parse(intArrayOf(0xF0, 1, 2, 3, 0xF7, 0xC2, 5, 0xE0, 0, 64, 0xB0, 64, 127))
        assertEquals(
            listOf(MidiEvent.ProgramChange(2, 5), MidiEvent.PitchBend(0, 8192), MidiEvent.ControlChange(0, 64, 127)),
            events,
        )
    }
}
