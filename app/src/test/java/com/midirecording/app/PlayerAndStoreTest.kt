package com.midirecording.app

import com.midirecording.app.data.ProjectStore
import com.midirecording.app.engine.Player
import com.midirecording.app.model.ControlEvent
import com.midirecording.app.model.Note
import com.midirecording.app.model.Project
import com.midirecording.app.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerAndStoreTest {
    private val project = Project(
        bpm = 100.0,
        tracks = listOf(
            Track(
                id = 1, name = "Piano", channel = 0, program = 4,
                notes = listOf(Note(60, 100, 0, 480), Note(62, 90, 480, 480)),
                controls = listOf(ControlEvent(0, 0xB0, 64, 127)),
            ),
            Track(id = 2, name = "Bajo", channel = 1, notes = listOf(Note(40, 100, 960, 240)), muted = true),
        ),
        nextTrackId = 3,
    )

    @Test
    fun eventsAreOrderedAndSkipEarlierNotes() {
        val events = Player.buildEvents(project, fromTick = 480)
        assertEquals(listOf(480L, 480L, 960L, 960L, 1200L), events.map { it.tick })
        // program change primero, luego la nota de la pista 1 que empieza en 480
        assertEquals(0xC0, events[0].bytes[0].toInt() and 0xF0)
        assertEquals(0x90, events[1].bytes[0].toInt() and 0xF0)
        // en 960: primero el note off, luego el note on
        assertEquals(0x80, events[2].bytes[0].toInt() and 0xF0)
        assertEquals(0x91, events[3].bytes[0].toInt() and 0xFF)
    }

    @Test
    fun soloOverridesMute() {
        assertEquals(setOf(1L), project.audibleTrackIds())
        val soloed = project.copy(tracks = project.tracks.map { if (it.id == 2L) it.copy(solo = true) else it })
        assertEquals(setOf(2L), soloed.audibleTrackIds())
    }

    @Test
    fun jsonRoundTrip() {
        assertEquals(project, ProjectStore.fromJson(ProjectStore.toJson(project)))
    }
}
