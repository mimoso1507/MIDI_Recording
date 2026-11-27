package com.midirecording.app

import com.midirecording.app.engine.Recorder
import com.midirecording.app.midi.MidiEvent
import com.midirecording.app.model.Note
import com.midirecording.app.model.PPQ
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecorderTest {
    // A 120 BPM una negra dura 500 ms.
    private val beat = 500_000_000L

    @Test
    fun convertsTimestampsToTicks() {
        val r = Recorder()
        r.start(fromTick = PPQ.toLong(), nanos = 1_000L, bpm = 120.0)
        r.onEvent(MidiEvent.NoteOn(2, 60, 100), 1_000L + beat)
        r.onEvent(MidiEvent.NoteOff(2, 60, 0), 1_000L + 2 * beat)
        val result = r.stop(1_000L + 3 * beat)!!
        assertEquals(listOf(Note(60, 100, 2L * PPQ, PPQ.toLong())), result.notes)
        assertEquals(2, result.channel)
    }

    @Test
    fun heldNotesAreClosedOnStop() {
        val r = Recorder()
        r.start(0, 0, 120.0)
        r.onEvent(MidiEvent.NoteOn(0, 64, 80), 0)
        val result = r.stop(beat)!!
        assertEquals(listOf(Note(64, 80, 0, PPQ.toLong())), result.notes)
    }

    @Test
    fun emptyTakeReturnsNull() {
        val r = Recorder()
        r.start(0, 0, 120.0)
        assertNull(r.stop(beat))
    }
}
