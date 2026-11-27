package com.midirecording.app.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Sintetizador SoundFont sencillo: 16 canales MIDI, envolvente de volumen
 * DAHDSR, bucles, pan, pitch bend, pedal de sustain y robo de voces.
 * No usa Android, así que se puede probar en el ordenador.
 */
class SynthEngine(@Volatile var soundFont: SoundFont?, private val outputRate: Int) {

    private class Channel {
        var program = 0
        var bank = 0
        var volume = 100
        var expression = 127
        var pan = 64
        var sustain = false
        var bendSemitones = 0f
        var bendRange = 2f
        var rpn = 0x3FFF
    }

    private enum class Stage { DELAY, ATTACK, HOLD, DECAY, SUSTAIN, RELEASE, DONE }

    private inner class Voice {
        var active = false
        var channel = 0
        var key = 0
        var region: Region? = null
        var pos = 0.0
        var baseStep = 0.0
        var gainL = 0f
        var gainR = 0f
        var stage = Stage.DONE
        var stageTime = 0f
        var level = 0f
        var releaseFrom = 0f
        var held = true
        var sustained = false
        var age = 0L
    }

    private val channels = Array(16) { Channel() }
    private val voices = Array(MAX_VOICES) { Voice() }
    private var ageCounter = 0L
    private val dt = 1f / outputRate

    var masterGain = 0.8f

    val activeVoices: Int get() = voices.count { it.active }

    // ---------- MIDI ----------

    fun handle(data: ByteArray) {
        if (data.isEmpty()) return
        val status = data[0].toInt() and 0xFF
        val ch = status and 0x0F
        val d1 = if (data.size > 1) data[1].toInt() and 0x7F else 0
        val d2 = if (data.size > 2) data[2].toInt() and 0x7F else 0
        when (status and 0xF0) {
            0x90 -> if (d2 == 0) noteOff(ch, d1) else noteOn(ch, d1, d2)
            0x80 -> noteOff(ch, d1)
            0xB0 -> controlChange(ch, d1, d2)
            0xC0 -> channels[ch].program = d1
            0xE0 -> {
                val value = d1 or (d2 shl 7)
                channels[ch].bendSemitones = (value - 8192) / 8192f * channels[ch].bendRange
            }
        }
    }

    fun noteOn(ch: Int, key: Int, velocity: Int) {
        val sf = soundFont ?: return
        val c = channels[ch]
        val bank = if (ch == 9) 128 else c.bank
        val preset = sf.find(bank, c.program) ?: return
        for (r in preset.regions) {
            if (key < r.keyLo || key > r.keyHi || velocity < r.velLo || velocity > r.velHi) continue
            if (r.exclusiveClass != 0) {
                voices.forEach { v ->
                    if (v.active && v.channel == ch && v.region?.exclusiveClass == r.exclusiveClass) v.active = false
                }
            }
            startVoice(ch, key, velocity, r)
        }
    }

    private fun startVoice(ch: Int, key: Int, velocity: Int, r: Region) {
        val v = voices.firstOrNull { !it.active } ?: voices.minByOrNull { if (it.held) it.age + Long.MAX_VALUE / 2 else it.age }!!
        v.active = true
        v.channel = ch
        v.key = key
        v.region = r
        v.pos = r.start.toDouble()
        val semis = (key - r.rootKey) * r.scaleTuning / 100.0 + r.tuneCents / 100.0
        v.baseStep = 2.0.pow(semis / 12.0) * r.sampleRate / outputRate
        val c = channels[ch]
        val velGain = (velocity / 127f).let { it * it }
        // Como FluidSynth y los EMU: la atenuación del SF2 se aplica al 40 %.
        val attenuation = 10f.pow(-r.attenuationCb * 0.4f / 200f)
        val panPos = ((r.pan / 500f) + (c.pan - 64) / 64f).coerceIn(-1f, 1f)
        val angle = (panPos + 1f) * (PI / 4).toFloat()
        val chanGain = (c.volume / 127f).let { it * it } * (c.expression / 127f)
        val g = velGain * attenuation * chanGain * masterGain
        v.gainL = g * cos(angle)
        v.gainR = g * sin(angle)
        v.stage = Stage.DELAY
        v.stageTime = 0f
        v.level = 0f
        v.held = true
        v.sustained = false
        v.age = ageCounter++
    }

    fun noteOff(ch: Int, key: Int) {
        val sustain = channels[ch].sustain
        voices.forEach { v ->
            if (v.active && v.channel == ch && v.key == key && v.held) {
                v.held = false
                if (sustain) v.sustained = true else release(v)
            }
        }
    }

    private fun release(v: Voice) {
        if (v.stage == Stage.RELEASE || v.stage == Stage.DONE) return
        v.releaseFrom = v.level
        v.stage = Stage.RELEASE
        v.stageTime = 0f
    }

    private fun controlChange(ch: Int, cc: Int, value: Int) {
        val c = channels[ch]
        when (cc) {
            0 -> c.bank = value
            7 -> c.volume = value
            10 -> c.pan = value
            11 -> c.expression = value
            64 -> {
                c.sustain = value >= 64
                if (!c.sustain) voices.forEach { v ->
                    if (v.active && v.channel == ch && v.sustained) {
                        v.sustained = false
                        release(v)
                    }
                }
            }
            100 -> c.rpn = (c.rpn and 0x3F80) or value
            101 -> c.rpn = (c.rpn and 0x7F) or (value shl 7)
            6 -> if (c.rpn == 0) c.bendRange = value.toFloat()
            120 -> voices.forEach { if (it.channel == ch) it.active = false }
            121 -> {
                c.volume = 100; c.expression = 127; c.pan = 64; c.sustain = false; c.bendSemitones = 0f
            }
            123 -> voices.forEach { v -> if (v.active && v.channel == ch) { v.held = false; v.sustained = false; release(v) } }
        }
    }

    fun allSoundOff() {
        voices.forEach { it.active = false }
        channels.forEach { it.sustain = false }
    }

    // ---------- Audio ----------

    /** Mezcla [frames] muestras estéreo intercaladas en [out] (se sobrescribe). */
    fun render(out: FloatArray, frames: Int) {
        java.util.Arrays.fill(out, 0, frames * 2, 0f)
        val samples = soundFont?.samples ?: return
        val sampleCount = samples.limit()
        for (v in voices) {
            if (!v.active) continue
            val r = v.region ?: continue
            val bend = 2.0.pow(channels[v.channel].bendSemitones / 12.0)
            val step = v.baseStep * bend
            val looping = r.loopMode == 1 || (r.loopMode == 3 && (v.held || v.sustained))
            val loopLen = (r.loopEnd - r.loopStart).toDouble()
            val canLoop = looping && loopLen > 1 && r.loopEnd <= r.end
            val end = min(r.end, sampleCount - 1)
            var i = 0
            while (i < frames) {
                val env = envelope(v, r)
                if (!v.active) break
                var p = v.pos
                if (canLoop) {
                    while (p >= r.loopEnd) p -= loopLen
                } else if (p >= end - 1) {
                    v.active = false
                    break
                }
                val idx = p.toInt()
                val frac = (p - idx).toFloat()
                val s0 = samples.get(idx)
                val s1 = if (idx + 1 < sampleCount) samples.get(idx + 1) else s0
                val s = (s0 + (s1 - s0) * frac) / 32768f * env
                out[i * 2] += s * v.gainL
                out[i * 2 + 1] += s * v.gainR
                v.pos = p + step
                i++
            }
        }
        for (k in 0 until frames * 2) out[k] = out[k].coerceIn(-1f, 1f)
    }

    /** Avanza la envolvente una muestra y devuelve su nivel (0..1). */
    private fun envelope(v: Voice, r: Region): Float {
        v.stageTime += dt
        when (v.stage) {
            Stage.DELAY -> if (v.stageTime >= r.delay) next(v, Stage.ATTACK)
            Stage.ATTACK -> {
                v.level = if (r.attack <= 0f) 1f else (v.stageTime / r.attack).coerceAtMost(1f)
                if (v.level >= 1f) next(v, Stage.HOLD)
            }
            Stage.HOLD -> {
                v.level = 1f
                if (v.stageTime >= r.hold) next(v, Stage.DECAY)
            }
            Stage.DECAY -> {
                val sustainLevel = 10f.pow(-r.sustainCb / 200f)
                if (r.decay <= 0f) {
                    v.level = sustainLevel
                    next(v, Stage.SUSTAIN)
                } else {
                    // Caída lineal en dB: 100 dB en el tiempo de decay.
                    val db = -100f * (v.stageTime / r.decay)
                    val lvl = 10f.pow(db / 20f)
                    if (lvl <= sustainLevel) {
                        v.level = sustainLevel
                        next(v, Stage.SUSTAIN)
                    } else {
                        v.level = lvl
                    }
                }
                if (v.level < SILENCE) v.active = false
            }
            Stage.SUSTAIN -> if (v.level < SILENCE) v.active = false
            Stage.RELEASE -> {
                val rel = r.release.coerceAtLeast(0.006f)
                val db = -100f * (v.stageTime / rel)
                v.level = v.releaseFrom * 10f.pow(db / 20f)
                if (v.level < SILENCE) v.active = false
            }
            Stage.DONE -> v.active = false
        }
        return v.level
    }

    private fun next(v: Voice, stage: Stage) {
        v.stage = stage
        v.stageTime = 0f
    }

    companion object {
        const val MAX_VOICES = 64
        const val SILENCE = 0.0001f
    }
}
