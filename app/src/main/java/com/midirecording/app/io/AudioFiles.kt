package com.midirecording.app.io

import com.midirecording.app.model.AUDIO_RATE
import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.GetAudio
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel

/** Destino de la mezcla exportada: recibe audio estéreo intercalado en float (-1..1). */
interface AudioSink : Closeable {
    fun write(stereo: FloatArray, frames: Int)
}

/** WAV PCM 16 bits estéreo. Escribe primero el audio y al cerrar corrige la cabecera. */
class WavWriter(private val file: File, private val rate: Int = AUDIO_RATE) : AudioSink {
    private val raf = RandomAccessFile(file, "rw").apply {
        setLength(0)
        write(ByteArray(44))
    }
    private var dataBytes = 0L
    private val buf = ByteBuffer.allocate(4096 * 4).order(ByteOrder.LITTLE_ENDIAN)

    override fun write(stereo: FloatArray, frames: Int) {
        var i = 0
        while (i < frames * 2) {
            buf.clear()
            while (i < frames * 2 && buf.remaining() >= 2) {
                buf.putShort((stereo[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                i++
            }
            raf.write(buf.array(), 0, buf.position())
            dataBytes += buf.position()
        }
    }

    override fun close() {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(rate)
            .putInt(rate * 4).putShort(4).putShort(16)
        h.put("data".toByteArray()).putInt(dataBytes.toInt())
        raf.seek(0)
        raf.write(h.array())
        raf.close()
    }
}

/** MP3 estéreo con el codificador LAME en Java (jump3r, LGPL). */
class Mp3Writer(private val out: OutputStream, rate: Int = AUDIO_RATE, bitrateKbps: Int = 192) : AudioSink {
    private val lame = Lame()
    private val gfp: LameGlobalFlags
    private val mp3Buf = ByteArray(1 shl 16)
    private var left = IntArray(0)
    private var right = IntArray(0)

    init {
        val ga = GainAnalysis()
        val bs = BitStream()
        val presets = Presets()
        val qupvt = QuantizePVT()
        val qu = Quantize()
        val vbr = VBRTag()
        val ver = Version()
        val id3 = ID3Tag()
        val rv = Reservoir()
        val tak = Takehiro()
        val parse = Parse()
        val mpg = MPGLib()
        val intf = Interface()
        val common = Common()
        val gaud = GetAudio()
        lame.setModules(ga, bs, presets, qupvt, qu, vbr, ver, id3, mpg)
        bs.setModules(ga, mpg, ver, vbr)
        id3.setModules(bs, ver)
        presets.setModules(lame)
        qu.setModules(bs, rv, qupvt, tak)
        qupvt.setModules(tak, rv, lame.enc.psy)
        rv.setModules(bs)
        tak.setModules(qupvt)
        vbr.setModules(lame, bs, ver)
        gaud.setModules(parse, mpg)
        parse.setModules(ver, id3, presets)
        mpg.setModules(intf, common)
        intf.setModules(vbr, common)

        gfp = lame.lame_init()
        gfp.num_channels = 2
        gfp.in_samplerate = rate
        gfp.out_samplerate = rate
        gfp.mode = MPEGMode.JOINT_STEREO
        gfp.brate = bitrateKbps
        gfp.quality = 5
        id3.id3tag_init(gfp)
        gfp.write_id3tag_automatic = false
        gfp.findReplayGain = false
        if (lame.lame_init_params(gfp) < 0) throw IllegalStateException("No se pudo iniciar el codificador MP3")
    }

    override fun write(stereo: FloatArray, frames: Int) {
        if (left.size < frames) {
            left = IntArray(frames)
            right = IntArray(frames)
        }
        for (i in 0 until frames) {
            // lame_encode_buffer_int espera enteros de 32 bits a escala completa
            left[i] = (stereo[i * 2].coerceIn(-1f, 1f) * 32767f).toInt() shl 16
            right[i] = (stereo[i * 2 + 1].coerceIn(-1f, 1f) * 32767f).toInt() shl 16
        }
        var offset = 0
        while (offset < frames) {
            val n = minOf(1152 * 4, frames - offset)
            val l = if (offset == 0 && n == left.size) left else left.copyOfRange(offset, offset + n)
            val r = if (offset == 0 && n == right.size) right else right.copyOfRange(offset, offset + n)
            val bytes = lame.lame_encode_buffer_int(gfp, l, r, n, mp3Buf, 0, mp3Buf.size)
            if (bytes > 0) out.write(mp3Buf, 0, bytes)
            offset += n
        }
    }

    override fun close() {
        val bytes = lame.lame_encode_flush(gfp, mp3Buf, 0, mp3Buf.size)
        if (bytes > 0) out.write(mp3Buf, 0, bytes)
        lame.lame_close(gfp)
        out.close()
    }
}

/** Lee un archivo PCM 16 bits estéreo de la carpeta de audio sin cargarlo en el heap. */
object PcmFile {
    fun map(file: File): ShortBuffer {
        RandomAccessFile(file, "r").use { raf ->
            val map = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
            return map.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        }
    }

    /** Pico por cada bloque de [block] frames, de 0 a 1, para dibujar la forma de onda. */
    fun peaks(file: File, block: Int = 512): FloatArray {
        val s = map(file)
        val frames = s.limit() / 2
        val out = FloatArray((frames + block - 1) / block)
        for (b in out.indices) {
            var m = 0
            val from = b * block
            val to = minOf(frames, from + block)
            var f = from
            while (f < to) {
                val v = maxOf(kotlin.math.abs(s.get(f * 2).toInt()), kotlin.math.abs(s.get(f * 2 + 1).toInt()))
                if (v > m) m = v
                f += 4
            }
            out[b] = m / 32768f
        }
        return out
    }
}
