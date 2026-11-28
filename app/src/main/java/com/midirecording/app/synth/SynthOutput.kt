package com.midirecording.app.synth

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.midirecording.app.engine.MidiOut
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Hace sonar el [SynthEngine] por la salida de audio del teléfono
 * (auriculares, altavoz o Bluetooth de audio).
 */
class SynthOutput(soundFont: SoundFont?) : MidiOut {
    private val engine = SynthEngine(soundFont, RATE)
    private val queue = ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var thread: Thread? = null

    var soundFont: SoundFont?
        get() = engine.soundFont
        set(value) {
            queue.clear()
            engine.soundFont = value
        }

    override fun send(data: ByteArray) {
        queue.add(data)
        ensureRunning()
    }

    @Synchronized
    private fun ensureRunning() {
        if (thread != null) return
        val t = Thread(::run, "sf2-synth")
        thread = t
        t.start()
    }

    fun release() {
        val t = thread ?: return
        thread = null
        t.interrupt()
        try {
            t.join(300)
        } catch (_: InterruptedException) {
        }
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val buffer = FloatArray(BLOCK * 2)
        try {
            track.play()
            val me = Thread.currentThread()
            while (!me.isInterrupted) {
                while (true) {
                    val msg = queue.poll() ?: break
                    engine.handle(msg)
                }
                engine.render(buffer, BLOCK)
                if (track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING) < 0) break
            }
        } finally {
            runCatching { track.pause() }
            runCatching { track.flush() }
            track.release()
        }
    }

    private companion object {
        const val RATE = 44100
        const val BLOCK = 128
    }
}
