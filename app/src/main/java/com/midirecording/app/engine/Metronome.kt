package com.midirecording.app.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.midirecording.app.model.PPQ
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * Metrónomo por el audio del teléfono. Genera los clics muestra a muestra a
 * partir del tempo, así que no se desfasa aunque la reproducción dure mucho.
 * El primer tiempo de cada compás suena más agudo.
 */
class Metronome {
    @Volatile private var thread: Thread? = null

    @Volatile var volume: Float = 0.8f

    fun start(fromTick: Long, bpm: Double, beatsPerBar: Int) {
        stop()
        val t = Thread({ run(fromTick, bpm, beatsPerBar) }, "metronome")
        thread = t
        t.start()
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

    private fun run(fromTick: Long, bpm: Double, beatsPerBar: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val high = click(1760.0)
        val low = click(1175.0)
        val samplesPerBeat = RATE * 60.0 / bpm
        val firstBeat = ceil(fromTick.toDouble() / PPQ).toLong()
        val firstOffset = (firstBeat * PPQ - fromTick).toDouble() / PPQ * samplesPerBeat
        val buffer = ShortArray(256)
        var sample = 0L
        try {
            track.play()
            val me = Thread.currentThread()
            while (!me.isInterrupted) {
                val vol = volume
                for (i in buffer.indices) {
                    val rel = (sample + i) - firstOffset
                    var v = 0
                    if (rel >= 0) {
                        val k = floor(rel / samplesPerBeat).toLong()
                        val within = (rel - k * samplesPerBeat).toInt()
                        val wave = if ((firstBeat + k) % beatsPerBar == 0L) high else low
                        if (within < wave.size) v = (wave[within] * vol).toInt()
                    }
                    buffer[i] = v.toShort()
                }
                if (track.write(buffer, 0, buffer.size) < 0) break
                sample += buffer.size
            }
        } finally {
            runCatching { track.pause() }
            runCatching { track.flush() }
            track.release()
        }
    }

    private fun click(freq: Double): FloatArray {
        val length = (RATE * 0.035).toInt()
        return FloatArray(length) { i ->
            val t = i.toDouble() / RATE
            (sin(2 * PI * freq * t) * exp(-t * 90.0) * 30000.0).toFloat()
        }
    }

    private companion object {
        const val RATE = 44100
    }
}
