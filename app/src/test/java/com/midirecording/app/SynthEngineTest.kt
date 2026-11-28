package com.midirecording.app

import com.midirecording.app.synth.SoundFont
import com.midirecording.app.synth.SynthEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/** Usa el GeneralUser GS incluido en la app. */
class SynthEngineTest {
    private val file = listOf(
        File("src/main/assets/soundfonts/GeneralUser-GS.sf2"),
        File("app/src/main/assets/soundfonts/GeneralUser-GS.sf2"),
        File(System.getProperty("sf2.path") ?: "none"),
    ).firstOrNull { it.exists() }

    private fun render(sf: SoundFont, program: Int, key: Int, seconds: Float, channel: Int = 0): FloatArray {
        val engine = SynthEngine(sf, 44100)
        engine.handle(byteArrayOf((0xC0 or channel).toByte(), program.toByte()))
        engine.handle(byteArrayOf((0x90 or channel).toByte(), key.toByte(), 100))
        val frames = (44100 * seconds).toInt()
        val out = FloatArray(frames * 2)
        val block = FloatArray(256)
        var f = 0
        while (f < frames) {
            val n = minOf(128, frames - f)
            engine.render(block, n)
            System.arraycopy(block, 0, out, f * 2, n * 2)
            f += n
        }
        return out
    }

    private fun rms(x: FloatArray) = sqrt(x.fold(0.0) { a, v -> a + v * v } / x.size)

    @Test
    fun parsesGeneralUserAndPlaysPianoInTune() {
        assumeTrue(file != null)
        val sf = SoundFont.load(file!!)
        assertTrue("presets: ${sf.presets.size}", sf.presets.size > 200)
        assertTrue(sf.find(0, 0)!!.regions.isNotEmpty())

        val out = render(sf, 0, 69, 1.0f) // La4 = 440 Hz
        assertTrue("rms ${rms(out)}", rms(out) > 0.01)
        assertTrue(out.all { !it.isNaN() && abs(it) <= 1f })
        // Frecuencia por autocorrelación del canal izquierdo, entre 0.2 y 0.5 s
        val from = (44100 * 0.2).toInt()
        val n = (44100 * 0.3).toInt()
        var bestLag = 0
        var best = Double.NEGATIVE_INFINITY
        for (lag in 50..200) {
            var acc = 0.0
            for (i in from until from + n) acc += out[i * 2] * out[(i + lag) * 2]
            if (acc > best) { best = acc; bestLag = lag }
        }
        val hz = 44100.0 / bestLag
        assertEquals("rms ${rms(out)}", 440.0, hz, 10.0)
    }

    @Test
    fun drumsOnChannel10AndStringsSustain() {
        assumeTrue(file != null)
        val sf = SoundFont.load(file!!)
        assertTrue(rms(render(sf, 0, 38, 0.3f, channel = 9)) > 0.005) // caja
        val strings = render(sf, 48, 60, 3f)
        val tail = strings.copyOfRange(44100 * 2 * 2, strings.size)
        assertTrue("las cuerdas deben seguir sonando con la tecla pulsada", rms(tail) > 0.005)
    }
}
