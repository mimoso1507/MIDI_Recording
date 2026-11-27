package com.midirecording.app.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.midirecording.app.io.PcmFile
import com.midirecording.app.model.AUDIO_RATE
import com.midirecording.app.model.Project
import com.midirecording.app.model.TimeMath
import java.io.File
import java.nio.ShortBuffer

/** Reproduce las pistas de audio importadas (WAV/MP3) junto con el MIDI. */
class AudioClipPlayer(private val audioDir: File) {
    private class Clip(val trackId: Long, val pcm: ShortBuffer, val startFrame: Long, val frames: Long)

    @Volatile private var thread: Thread? = null
    @Volatile private var audible: Set<Long> = emptySet()

    fun start(project: Project, fromTick: Long) {
        stop()
        val clips = project.tracks.mapNotNull { t ->
            val a = t.audio ?: return@mapNotNull null
            val file = File(audioDir, a.file)
            if (!file.exists()) return@mapNotNull null
            Clip(t.id, PcmFile.map(file), TimeMath.ticksToNanos(a.startTick, project.bpm) * AUDIO_RATE / 1_000_000_000L, a.frames)
        }
        if (clips.isEmpty()) return
        audible = project.audibleTrackIds()
        val fromFrame = TimeMath.ticksToNanos(fromTick, project.bpm) * AUDIO_RATE / 1_000_000_000L
        val t = Thread({ run(clips, fromFrame) }, "audio-clips")
        thread = t
        t.start()
    }

    fun updateAudible(project: Project) {
        audible = project.audibleTrackIds()
    }

    fun stop() {
        val t = thread ?: return
        thread = null
        t.interrupt()
        try {
            t.join(300)
        } catch (_: InterruptedException) {
        }
    }

    private fun run(clips: List<Clip>, fromFrame: Long) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioTrack.getMinBufferSize(AUDIO_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(AUDIO_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
            )
            .setBufferSizeInBytes(minBuf)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val block = 256
        val buf = ShortArray(block * 2)
        var frame = fromFrame
        val end = clips.maxOf { it.startFrame + it.frames }
        try {
            track.play()
            val me = Thread.currentThread()
            while (!me.isInterrupted && frame < end) {
                val on = audible
                for (i in 0 until block) {
                    var l = 0
                    var r = 0
                    for (c in clips) {
                        if (c.trackId !in on) continue
                        val f = frame + i - c.startFrame
                        if (f < 0 || f >= c.frames) continue
                        val idx = (f * 2).toInt()
                        if (idx + 1 >= c.pcm.limit()) continue
                        l += c.pcm.get(idx)
                        r += c.pcm.get(idx + 1)
                    }
                    buf[i * 2] = l.coerceIn(-32768, 32767).toShort()
                    buf[i * 2 + 1] = r.coerceIn(-32768, 32767).toShort()
                }
                if (track.write(buf, 0, buf.size) < 0) break
                frame += block
            }
        } finally {
            runCatching { track.pause() }
            runCatching { track.flush() }
            track.release()
        }
    }
}
