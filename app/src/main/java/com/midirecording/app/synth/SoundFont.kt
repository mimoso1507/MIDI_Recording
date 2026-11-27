package com.midirecording.app.synth

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * Una zona ya resuelta (preset + instrumento + muestra) lista para sonar.
 * Tiempos de envolvente en segundos, atenuación en centibelios.
 */
class Region(
    val keyLo: Int,
    val keyHi: Int,
    val velLo: Int,
    val velHi: Int,
    val start: Int,
    val end: Int,
    val loopStart: Int,
    val loopEnd: Int,
    /** 0 = sin bucle, 1 = bucle continuo, 3 = bucle hasta soltar. */
    val loopMode: Int,
    val rootKey: Int,
    /** Afinación total en cents (coarse*100 + fine + corrección de la muestra). */
    val tuneCents: Int,
    val scaleTuning: Int,
    val sampleRate: Int,
    val attenuationCb: Float,
    /** -500 (izquierda) .. 500 (derecha). */
    val pan: Float,
    val delay: Float,
    val attack: Float,
    val hold: Float,
    val decay: Float,
    /** Nivel de sustain como atenuación en cB (0 = máximo). */
    val sustainCb: Float,
    val release: Float,
    val exclusiveClass: Int,
)

class Preset(val bank: Int, val program: Int, val name: String, val regions: List<Region>)

/**
 * Lector de archivos SoundFont 2 (.sf2). Las muestras se leen mapeando el
 * archivo en memoria, así que no ocupan el heap de Java.
 */
class SoundFont private constructor(
    val name: String,
    val samples: ShortBuffer,
    val presets: List<Preset>,
) {
    private val byKey = presets.associateBy { it.bank * 128 + it.program }

    /** Busca el preset; si no existe en ese banco, usa el banco 0 (o 128 para batería). */
    fun find(bank: Int, program: Int): Preset? =
        byKey[bank * 128 + program]
            ?: byKey[(if (bank >= 128) 128 else 0) * 128 + program]
            ?: if (bank >= 128) byKey[128 * 128] else byKey[program] ?: presets.firstOrNull { it.bank == 0 }

    companion object {
        fun load(file: File): SoundFont {
            val channel = RandomAccessFile(file, "r").channel
            val map = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
            channel.close()
            map.order(ByteOrder.LITTLE_ENDIAN)
            return parse(map, file.nameWithoutExtension)
        }

        fun parse(buf: ByteBuffer, name: String): SoundFont {
            buf.order(ByteOrder.LITTLE_ENDIAN)
            if (fourcc(buf, 0) != "RIFF" || fourcc(buf, 8) != "sfbk") throw IOException("No es un archivo SoundFont 2")
            val riffEnd = min(buf.limit(), 8 + buf.getInt(4))
            var smplOffset = -1
            var smplSize = 0
            val pdta = HashMap<String, Pair<Int, Int>>()
            var pos = 12
            while (pos + 8 <= riffEnd) {
                val id = fourcc(buf, pos)
                val size = buf.getInt(pos + 4)
                if (id == "LIST") {
                    val listType = fourcc(buf, pos + 8)
                    var sub = pos + 12
                    val listEnd = pos + 8 + size
                    while (sub + 8 <= listEnd) {
                        val subId = fourcc(buf, sub)
                        val subSize = buf.getInt(sub + 4)
                        if (listType == "sdta" && subId == "smpl") {
                            smplOffset = sub + 8
                            smplSize = subSize
                        } else if (listType == "pdta") {
                            pdta[subId] = (sub + 8) to subSize
                        }
                        sub += 8 + subSize + (subSize and 1)
                    }
                }
                pos += 8 + size + (size and 1)
            }
            if (smplOffset < 0) throw IOException("El SoundFont no tiene muestras")
            val dup = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            dup.position(smplOffset)
            dup.limit(smplOffset + smplSize)
            val samples = dup.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val presets = Hydra(buf, pdta).buildPresets()
            return SoundFont(name, samples, presets)
        }

        private fun fourcc(buf: ByteBuffer, at: Int): String =
            String(CharArray(4) { (buf.get(at + it).toInt() and 0xFF).toChar() })
    }
}

/** Interpreta los bloques "pdta" (presets, instrumentos, generadores, muestras). */
private class Hydra(private val buf: ByteBuffer, private val chunks: Map<String, Pair<Int, Int>>) {

    private class Gen(val oper: Int, val amount: Int)
    private class SampleHeader(
        val start: Int, val end: Int, val loopStart: Int, val loopEnd: Int,
        val rate: Int, val pitch: Int, val correction: Int,
    )

    private fun records(id: String, size: Int): Pair<Int, Int> {
        val (off, len) = chunks[id] ?: throw IOException("Falta el bloque $id")
        return off to len / size
    }

    private fun u16(at: Int) = buf.getShort(at).toInt() and 0xFFFF
    private fun s16(at: Int) = buf.getShort(at).toInt()

    private fun name(at: Int): String {
        val sb = StringBuilder()
        for (i in 0 until 20) {
            val c = buf.get(at + i).toInt() and 0xFF
            if (c == 0) break
            sb.append(c.toChar())
        }
        return sb.toString().trim()
    }

    private fun gens(id: String): List<Gen> {
        val (off, n) = records(id, 4)
        return List(n) { Gen(u16(off + it * 4), s16(off + it * 4 + 2)) }
    }

    private fun bags(id: String): List<Int> {
        val (off, n) = records(id, 4)
        return List(n) { u16(off + it * 4) }
    }

    /** Zonas de cada elemento: lista de generadores (oper -> valor). */
    private fun zones(firstBag: Int, nextBag: Int, bagIdx: List<Int>, genList: List<Gen>): List<Map<Int, Int>> =
        (firstBag until nextBag).map { b ->
            val from = bagIdx[b]
            val to = if (b + 1 < bagIdx.size) bagIdx[b + 1] else genList.size
            val m = LinkedHashMap<Int, Int>()
            for (g in from until min(to, genList.size)) m[genList[g].oper] = genList[g].amount
            m
        }

    fun buildPresets(): List<Preset> {
        val (shOff, shN) = records("shdr", 46)
        val samples = List(shN) { i ->
            val a = shOff + i * 46
            SampleHeader(
                buf.getInt(a + 20), buf.getInt(a + 24), buf.getInt(a + 28), buf.getInt(a + 32),
                buf.getInt(a + 36), buf.get(a + 40).toInt() and 0xFF, buf.get(a + 41).toInt(),
            )
        }
        val iGens = gens("igen")
        val iBags = bags("ibag")
        val (instOff, instN) = records("inst", 22)
        val instZones = List(max(0, instN - 1)) { i ->
            zones(u16(instOff + i * 22 + 20), u16(instOff + (i + 1) * 22 + 20), iBags, iGens)
        }
        val pGens = gens("pgen")
        val pBags = bags("pbag")
        val (phOff, phN) = records("phdr", 38)
        val result = ArrayList<Preset>()
        for (p in 0 until phN - 1) {
            val a = phOff + p * 38
            val pz = zones(u16(a + 24), u16(phOff + (p + 1) * 38 + 24), pBags, pGens)
            val pGlobal = pz.firstOrNull()?.takeIf { GEN_INSTRUMENT !in it } ?: emptyMap()
            val regions = ArrayList<Region>()
            for (zone in pz) {
                val inst = zone[GEN_INSTRUMENT] ?: continue
                if (inst !in instZones.indices) continue
                val pAll = HashMap(pGlobal).apply { putAll(zone) }
                val iz = instZones[inst]
                val iGlobal = iz.firstOrNull()?.takeIf { GEN_SAMPLE !in it } ?: emptyMap()
                for (izone in iz) {
                    val sampleId = izone[GEN_SAMPLE] ?: continue
                    if (sampleId !in samples.indices) continue
                    val iAll = HashMap(iGlobal).apply { putAll(izone) }
                    region(pAll, iAll, samples[sampleId])?.let { regions += it }
                }
            }
            result += Preset(u16(a + 22), u16(a + 20), name(a), regions)
        }
        return result
    }

    private fun region(p: Map<Int, Int>, i: Map<Int, Int>, s: SampleHeader): Region? {
        fun lo(v: Int?) = v?.and(0xFF)
        fun hi(v: Int?) = v?.shr(8)?.and(0xFF)
        val keyLo = max(lo(i[GEN_KEY_RANGE]) ?: 0, lo(p[GEN_KEY_RANGE]) ?: 0)
        val keyHi = min(hi(i[GEN_KEY_RANGE]) ?: 127, hi(p[GEN_KEY_RANGE]) ?: 127)
        val velLo = max(lo(i[GEN_VEL_RANGE]) ?: 0, lo(p[GEN_VEL_RANGE]) ?: 0)
        val velHi = min(hi(i[GEN_VEL_RANGE]) ?: 127, hi(p[GEN_VEL_RANGE]) ?: 127)
        if (keyLo > keyHi || velLo > velHi) return null

        fun inst(op: Int, def: Int) = i[op] ?: def
        fun sum(op: Int, def: Int) = (i[op] ?: def) + (p[op] ?: 0)
        fun addr(fine: Int, coarse: Int) = inst(fine, 0) + inst(coarse, 0) * 32768

        val start = s.start + addr(0, 4)
        val end = s.end + addr(1, 12)
        val loopStart = s.loopStart + addr(2, 45)
        val loopEnd = s.loopEnd + addr(3, 50)
        if (end <= start) return null
        val root = inst(GEN_ROOT_KEY, -1).let { if (it in 0..127) it else s.pitch.takeIf { p -> p in 0..127 } ?: 60 }
        return Region(
            keyLo = keyLo, keyHi = keyHi, velLo = velLo, velHi = velHi,
            start = start, end = end, loopStart = loopStart, loopEnd = loopEnd,
            loopMode = inst(54, 0) and 3,
            rootKey = root,
            tuneCents = sum(51, 0) * 100 + sum(52, 0) + s.correction,
            scaleTuning = inst(56, 100),
            sampleRate = if (s.rate > 0) s.rate else 44100,
            attenuationCb = sum(48, 0).coerceIn(0, 1440).toFloat(),
            pan = sum(17, 0).coerceIn(-500, 500).toFloat(),
            delay = timecents(sum(33, -12000)),
            attack = timecents(sum(34, -12000)),
            hold = timecents(sum(35, -12000)),
            decay = timecents(sum(36, -12000)),
            sustainCb = sum(37, 0).coerceIn(0, 1440).toFloat(),
            release = timecents(sum(38, -12000)),
            exclusiveClass = inst(57, 0),
        )
    }

    private fun timecents(tc: Int): Float =
        if (tc <= -12000) 0f else Math.pow(2.0, tc.coerceAtMost(8000) / 1200.0).toFloat()

    companion object {
        const val GEN_KEY_RANGE = 43
        const val GEN_VEL_RANGE = 44
        const val GEN_INSTRUMENT = 41
        const val GEN_SAMPLE = 53
        const val GEN_ROOT_KEY = 58
    }
}
