package com.midirecording.app.engine

import com.midirecording.app.model.Note
import com.midirecording.app.model.PPQ
import kotlin.math.max
import kotlin.math.roundToLong

/** Rejillas de cuantización disponibles. */
enum class QuantizeGrid(val label: String, val ticks: Long) {
    QUARTER("1/4", PPQ.toLong()),
    EIGHTH("1/8", PPQ / 2L),
    EIGHTH_TRIPLET("1/8 tresillo", PPQ / 3L),
    SIXTEENTH("1/16", PPQ / 4L),
    SIXTEENTH_TRIPLET("1/16 tresillo", PPQ / 6L),
    THIRTY_SECOND("1/32", PPQ / 8L),
}

object Quantizer {
    /**
     * Mueve el inicio de las notas que empiezan dentro de [startTick, endTick)
     * a la línea de rejilla más cercana. [strength] de 0 a 1 (1 = exacto).
     * Si [quantizeEnds] es true, también ajusta el final de la nota.
     */
    fun quantize(
        notes: List<Note>,
        gridTicks: Long,
        startTick: Long = 0,
        endTick: Long = Long.MAX_VALUE,
        strength: Float = 1f,
        quantizeEnds: Boolean = false,
    ): List<Note> {
        require(gridTicks > 0)
        return notes.map { n ->
            if (n.startTick < startTick || n.startTick >= endTick) return@map n
            val target = snap(n.startTick, gridTicks)
            val newStart = max(0L, n.startTick + ((target - n.startTick) * strength).roundToLong())
            val newDuration = if (quantizeEnds) {
                val endTarget = max(snap(n.endTick, gridTicks), target + gridTicks)
                max(1L, n.endTick + ((endTarget - n.endTick) * strength).roundToLong() - newStart)
            } else {
                n.durationTicks
            }
            n.copy(startTick = newStart, durationTicks = newDuration)
        }.sortedBy { it.startTick }
    }

    private fun snap(tick: Long, grid: Long): Long = ((tick.toDouble() / grid).roundToLong()) * grid
}
