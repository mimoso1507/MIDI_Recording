package com.midirecording.app

import com.midirecording.app.midi.ControlAction
import com.midirecording.app.midi.ControlMapper
import com.midirecording.app.midi.ControlMapper.Result
import com.midirecording.app.midi.MidiEvent
import com.midirecording.app.midi.MidiTrigger
import org.junit.Assert.assertEquals
import org.junit.Test

class ControlMapperTest {
    @Test
    fun learnsNoteAndTriggersItWithoutRecording() {
        val m = ControlMapper()
        m.startLearning(ControlAction.RECORD)
        val learned = m.process(MidiEvent.NoteOn(9, 36, 100))
        assertEquals(Result.Learned(ControlAction.RECORD, MidiTrigger(MidiTrigger.Kind.NOTE, 9, 36)), learned)
        assertEquals(Result.Consumed, m.process(MidiEvent.NoteOff(9, 36, 0)))
        assertEquals(Result.Triggered(ControlAction.RECORD), m.process(MidiEvent.NoteOn(9, 36, 90)))
        assertEquals(Result.PassThrough, m.process(MidiEvent.NoteOn(0, 60, 90)))
    }

    @Test
    fun ccTriggersOnlyOnPress() {
        val m = ControlMapper(mapOf(ControlAction.PLAY_PAUSE to MidiTrigger(MidiTrigger.Kind.CC, 0, 20)))
        assertEquals(Result.Triggered(ControlAction.PLAY_PAUSE), m.process(MidiEvent.ControlChange(0, 20, 127)))
        assertEquals(Result.Consumed, m.process(MidiEvent.ControlChange(0, 20, 127)))
        assertEquals(Result.Consumed, m.process(MidiEvent.ControlChange(0, 20, 0)))
        assertEquals(Result.Triggered(ControlAction.PLAY_PAUSE), m.process(MidiEvent.ControlChange(0, 20, 127)))
    }

    @Test
    fun relearningMovesButtonAndBindingsRoundTrip() {
        val t = MidiTrigger(MidiTrigger.Kind.CC, 1, 30)
        val m = ControlMapper(mapOf(ControlAction.STOP to t))
        m.startLearning(ControlAction.METRONOME)
        m.process(MidiEvent.ControlChange(1, 30, 127))
        assertEquals(mapOf(ControlAction.METRONOME to t), m.bindings())
        assertEquals(m.bindings(), ControlMapper.decode(ControlMapper.encode(m.bindings())))
    }
}
