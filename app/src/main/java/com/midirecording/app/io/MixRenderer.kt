package com.midirecording.app.io

import com.midirecording.app.engine.Player
import com.midirecording.app.model.AUDIO_RATE
import com.midirecording.app.model.Project
import com.midirecording.app.model.TimeMath
import com.midirecording.app.synth.SoundFont
import com.midirecording.app.synth.SynthEngine
import java.io.File
import java.nio.ShortBuffer

/**
 * Mezcla todo el proyecto sin tiempo real: las pistas MIDI con el
 * sintetizador SoundFont y las pistas de audio, respetando Mute y Solo.
 */
class MixRenderer(
    private val project: Project,
    soundFont: SoundFont?,
    private val audioDir: File,
) {
    private val engine = SynthEngine(soundFont, AUDIO_RATE)

    /** [onProgress] recibe de 0 a 1. Devuelve los frames escritos. */
    fun render(sink: AudioSink, tailSeconds: Double = 2.0, onProgress: (Float) -> Unit = {}): Long {
        val audible = project.audibleTrackIds()
        val events = Player.buildEvents(project, 0).filter { it.trackId in audible || it.kind == Player.Kind.PROGRAM }
        val clips = project.tracks.filter { it.id in audible && it.audio != null }.map { t ->
            val clip = t.audio!!
            val startFrame = TimeMath.ticksToNanos(clip.startTick, project.bpm) * AUDIO_RATE / 1_000_000_000L
            Triple(PcmFile.map(File(audioDir, clip.file)), startFrame, clip.frames)
        }
        val nanosPerTick = TimeMath.nanosPerTick(project.bpm)
        val endFrame = (project.endTick * nanosPerTick * AUDIO_RATE / 1e9).toLong() + (tailSeconds * AUDIO_RATE).toLong()
        val block = 64
        val buf = FloatArray(block * 2)
        var frame = 0L
        var next = 0
        while (frame < endFrame) {
            val blockEndNanos = (frame + block) * 1e9 / AUDIO_RATE
            while (next < events.size && events[next].tick * nanosPerTick < blockEndNanos) {
                engine.handle(events[next].bytes)
                next++
            }
            engine.render(buf, block)
            mixClips(clips, frame, buf, block)
            sink.write(buf, block)
            frame += block
            if (frame % (AUDIO_RATE * 2) < block) onProgress((frame.toFloat() / endFrame).coerceAtMost(1f))
        }
        sink.close()
        return frame
    }

    private fun mixClips(clips: List<Triple<ShortBuffer, Long, Long>>, frame: Long, buf: FloatArray, n: Int) {
        for ((pcm, start, frames) in clips) {
            for (i in 0 until n) {
                val f = frame + i - start
                if (f < 0 || f >= frames) continue
                val idx = (f * 2).toInt()
                if (idx + 1 >= pcm.limit()) continue
                buf[i * 2] = (buf[i * 2] + pcm.get(idx) / 32768f).coerceIn(-1f, 1f)
                buf[i * 2 + 1] = (buf[i * 2 + 1] + pcm.get(idx + 1) / 32768f).coerceIn(-1f, 1f)
            }
        }
    }
}
