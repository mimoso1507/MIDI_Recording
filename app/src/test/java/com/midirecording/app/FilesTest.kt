package com.midirecording.app

import com.midirecording.app.io.MidiFile
import com.midirecording.app.io.MixRenderer
import com.midirecording.app.io.Mp3Writer
import com.midirecording.app.io.WavWriter
import com.midirecording.app.model.ControlEvent
import com.midirecording.app.model.Note
import com.midirecording.app.model.Project
import com.midirecording.app.model.Track
import com.midirecording.app.synth.SoundFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class FilesTest {
    private val project = Project(
        bpm = 96.0,
        beatsPerBar = 3,
        tracks = listOf(
            Track(1, "Piano", channel = 0, program = 0,
                notes = listOf(Note(60, 100, 0, 480), Note(64, 90, 480, 240), Note(67, 80, 480, 960)),
                controls = listOf(ControlEvent(0, 0xB0, 64, 127), ControlEvent(1440, 0xB0, 64, 0))),
            Track(2, "Batería", channel = 9, notes = listOf(Note(36, 120, 0, 120), Note(38, 110, 480, 120))),
        ),
        nextTrackId = 3,
    )

    @Test
    fun midiFileRoundTrip() {
        val back = MidiFile.read(MidiFile.write(project))
        assertEquals(96.0, back.bpm, 0.01)
        assertEquals(3, back.beatsPerBar)
        assertEquals(project.tracks.map { it.name }, back.tracks.map { it.name })
        assertEquals(project.tracks.map { it.channel }, back.tracks.map { it.channel })
        assertEquals(project.tracks[0].program, back.tracks[0].program)
        assertEquals(project.tracks.map { it.notes.sortedBy { n -> n.startTick * 128 + n.pitch } },
            back.tracks.map { it.notes.sortedBy { n -> n.startTick * 128 + n.pitch } })
        assertEquals(project.tracks[0].controls, back.tracks[0].controls)
    }

    @Test
    fun formatZeroIsSplitByChannel() {
        // Formato 0, 96 ticks por negra, dos canales en una sola pista, con running status
        val track = byteArrayOf(
            0, 0x90.toByte(), 60, 100, 0, 61, 90,          // canal 1: dos notas (running status)
            0, 0x99.toByte(), 36, 100,                      // canal 10
            96, 0x80.toByte(), 60, 0, 0, 0x90.toByte(), 61, 0, // offs (vel 0 = off)
            0, 0x89.toByte(), 36, 0,
            0, 0xFF.toByte(), 0x2F, 0,
        )
        val data = "MThd".toByteArray() + byteArrayOf(0, 0, 0, 6, 0, 0, 0, 1, 0, 96) +
            "MTrk".toByteArray() + byteArrayOf(0, 0, 0, track.size.toByte()) + track
        val p = MidiFile.read(data)
        assertEquals(2, p.tracks.size)
        assertEquals(listOf(Note(60, 100, 0, 480), Note(61, 90, 0, 480)), p.tracks[0].notes)
        assertEquals(9, p.tracks[1].channel)
    }

    @Test
    fun exportsWavAndMp3() {
        val sf2 = File(System.getProperty("sf2.path") ?: "src/main/assets/soundfonts/GeneralUser-GS.sf2")
            .takeIf { it.exists() } ?: File("src/main/assets/soundfonts/GeneralUser-GS.sf2")
        val sf = SoundFont.load(sf2)
        val tmp = File.createTempFile("mix", ".wav")
        val frames = MixRenderer(project, sf, tmp.parentFile).render(WavWriter(tmp))
        assertEquals(44L + frames * 4, tmp.length())
        assertEquals("RIFF", String(tmp.readBytes().copyOfRange(0, 4)))

        val mp3 = File.createTempFile("mix", ".mp3")
        MixRenderer(project, sf, tmp.parentFile).render(Mp3Writer(FileOutputStream(mp3)))
        val bytes = mp3.readBytes()
        assertTrue("mp3 ${bytes.size}", bytes.size > 10_000)
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xE0, bytes[1].toInt() and 0xE0)
        tmp.delete(); mp3.delete()
    }
}
