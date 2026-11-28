package com.midirecording.app

import com.midirecording.app.data.ProjectStore
import com.midirecording.app.engine.QuantizeGrid
import com.midirecording.app.engine.Quantizer
import com.midirecording.app.model.Note
import com.midirecording.app.model.Project
import com.midirecording.app.model.Track
import com.midirecording.app.model.TrackGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class QuantizerAndGroupsTest {
    @Test
    fun quantizesStartsToNearestGridKeepingDuration() {
        val notes = listOf(Note(60, 100, 130, 200), Note(62, 100, 470, 100))
        val q = Quantizer.quantize(notes, QuantizeGrid.SIXTEENTH.ticks)
        assertEquals(listOf(Note(60, 100, 120, 200), Note(62, 100, 480, 100)), q)
    }

    @Test
    fun onlyNotesInsideRangeAreMoved() {
        val notes = listOf(Note(60, 100, 130, 200), Note(62, 100, 1010, 100))
        val q = Quantizer.quantize(notes, QuantizeGrid.QUARTER.ticks, startTick = 960, endTick = 1920)
        assertEquals(listOf(Note(60, 100, 130, 200), Note(62, 100, 960, 100)), q)
    }

    @Test
    fun quantizeEndsAndHalfStrength() {
        val q = Quantizer.quantize(listOf(Note(60, 100, 100, 300)), 120, quantizeEnds = true)
        assertEquals(Note(60, 100, 120, 240), q[0])
        val half = Quantizer.quantize(listOf(Note(60, 100, 100, 300)), 120, strength = 0.5f)
        assertEquals(110L, half[0].startTick)
    }

    private val grouped = Project(
        tracks = listOf(
            Track(1, "A", groupId = 1),
            Track(2, "B"),
            Track(3, "C", groupId = 1),
        ),
        groups = listOf(TrackGroup(1, "A + C")),
        nextGroupId = 2,
    )

    @Test
    fun groupTakesPlaceOfFirstMember() {
        val rows = grouped.rows()
        assertEquals(2, rows.size)
        assertEquals(setOf(1L, 3L), rows[0].trackIds)
        assertEquals(setOf(2L), rows[1].trackIds)
    }

    @Test
    fun groupsSurviveSave() {
        assertEquals(grouped, ProjectStore.fromJson(ProjectStore.toJson(grouped)))
    }
}
