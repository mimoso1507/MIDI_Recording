package com.midirecording.app.io

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.midirecording.app.model.AUDIO_RATE
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodifica WAV, MP3 (y lo que soporte el teléfono) a PCM 16 bits estéreo
 * a 44,1 kHz, que es el formato con el que la app mezcla y reproduce.
 */
object AudioDecoder {

    /** Devuelve el número de frames escritos en [target]. */
    fun decode(context: Context, uri: Uri, target: File, onProgress: (Float) -> Unit = {}): Long {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val trackIndex = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IOException("El archivo no tiene audio")
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        var inRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmFloat = false
        val resampler = Resampler()
        var frames = 0L
        val out = BufferedOutputStream(target.outputStream(), 1 shl 16)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            if (durationUs > 0) onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        inRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIndex >= 0 -> {
                        val buf = codec.getOutputBuffer(outIndex)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        frames += resampler.push(buf.order(ByteOrder.nativeOrder()), pcmFloat, channels, inRate, out)
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            out.close()
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        if (frames == 0L) throw IOException("No se pudo leer el audio")
        return frames
    }

    /** Convierte a estéreo 16 bits y remuestrea a 44,1 kHz con interpolación lineal. */
    private class Resampler {
        private var pos = 0.0
        private var prevL = 0f
        private var prevR = 0f
        private var havePrev = false
        private val outBuf = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)

        fun push(buf: ByteBuffer, isFloat: Boolean, channels: Int, rate: Int, out: java.io.OutputStream): Long {
            val bytesPerSample = if (isFloat) 4 else 2
            val inFrames = buf.remaining() / (bytesPerSample * channels)
            val step = rate.toDouble() / AUDIO_RATE
            var written = 0L
            fun sample(frame: Int, ch: Int): Float {
                val at = buf.position() + (frame * channels + ch) * bytesPerSample
                return if (isFloat) buf.getFloat(at) else buf.getShort(at) / 32768f
            }
            outBuf.clear()
            // pos se mide en frames de entrada, contando "prev" como frame -1
            while (true) {
                val i = pos.toInt()
                if (i >= inFrames) break
                val frac = (pos - i).toFloat()
                val l1 = sample(i, 0)
                val r1 = if (channels > 1) sample(i, 1) else l1
                val l0 = if (i == 0) (if (havePrev) prevL else l1) else sample(i - 1, 0)
                val r0 = if (i == 0) (if (havePrev) prevR else r1) else (if (channels > 1) sample(i - 1, 1) else l0)
                // Interpola entre el frame anterior y el actual
                val l = l0 + (l1 - l0) * frac
                val r = r0 + (r1 - r0) * frac
                if (outBuf.remaining() < 4) {
                    out.write(outBuf.array(), 0, outBuf.position())
                    outBuf.clear()
                }
                outBuf.putShort((l.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                outBuf.putShort((r.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                written++
                pos += step
            }
            out.write(outBuf.array(), 0, outBuf.position())
            if (inFrames > 0) {
                prevL = sample(inFrames - 1, 0)
                prevR = if (channels > 1) sample(inFrames - 1, 1) else prevL
                havePrev = true
            }
            pos -= inFrames
            return written
        }
    }
}
