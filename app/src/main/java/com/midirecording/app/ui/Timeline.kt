package com.midirecording.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.midirecording.app.model.GM_INSTRUMENTS
import com.midirecording.app.model.Note
import com.midirecording.app.model.PPQ
import com.midirecording.app.model.Project
import com.midirecording.app.model.Track
import com.midirecording.app.ui.theme.AppColors
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val HeaderWidth = 116.dp
private val RulerHeight = 28.dp
private val TrackHeight = 88.dp

private const val MIN_PX_PER_TICK = 0.004f
private const val MAX_PX_PER_TICK = 1.5f

/**
 * Vista de pistas horizontal estilo Audacity:
 * - toque: mueve el cursor
 * - mantener pulsado y arrastrar: selecciona un rango (en una o varias pistas)
 * - arrastrar con un dedo: desplaza
 * - pellizcar: zoom
 */
@Composable
fun Timeline(
    project: Project,
    recordingNotes: List<Note>,
    transport: TransportState,
    positionFlow: StateFlow<Long>,
    cursorTick: Long,
    selection: Selection?,
    onSetCursor: (Long) -> Unit,
    onSelect: (Selection?) -> Unit,
    onSelectTrack: (Long) -> Unit,
    onToggleMute: (Long) -> Unit,
    onToggleSolo: (Long) -> Unit,
    onInstrument: (Track) -> Unit,
    onRename: (Track) -> Unit,
    onDelete: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val trackPx = with(density) { TrackHeight.toPx() }
    val rulerPx = with(density) { RulerHeight.toPx() }

    var pxPerTick by remember { mutableFloatStateOf(0.1f) }
    var scrollX by remember { mutableFloatStateOf(0f) }
    var scrollY by remember { mutableFloatStateOf(0f) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var selecting by remember { mutableStateOf(false) }

    val recording = transport == TransportState.RECORDING
    val rowCount = project.tracks.size + if (recording) 1 else 0
    val positionState = positionFlow.collectAsStateWithLifecycle()

    val currentProject by rememberUpdatedState(project)
    val currentRows by rememberUpdatedState(rowCount)
    val currentOnSetCursor by rememberUpdatedState(onSetCursor)
    val currentOnSelect by rememberUpdatedState(onSelect)

    fun maxScrollY(): Float = max(0f, currentRows * trackPx - (viewSize.height - rulerPx))
    fun tickAt(x: Float): Long = ((scrollX + x) / pxPerTick).toLong().coerceAtLeast(0)
    fun rowAt(y: Float): Int = floor((y - rulerPx + scrollY) / trackPx).toInt()
    fun zoomBy(factor: Float, anchorX: Float) {
        val old = pxPerTick
        val new = (old * factor).coerceIn(MIN_PX_PER_TICK, MAX_PX_PER_TICK)
        val anchorTick = (scrollX + anchorX) / old
        pxPerTick = new
        scrollX = max(0f, anchorTick * new - anchorX)
    }

    // Sigue al cabezal mientras suena o graba.
    LaunchedEffect(transport) {
        if (transport == TransportState.PLAYING || transport == TransportState.RECORDING) {
            snapshotFlow { positionState.value }.collect { tick ->
                val w = viewSize.width
                val px = tick * pxPerTick - scrollX
                if (w > 0 && (px > w * 0.85f || px < 0f)) {
                    scrollX = max(0f, tick * pxPerTick - w * 0.15f)
                }
            }
        }
    }
    // Al empezar a grabar, muestra la fila de la toma nueva.
    LaunchedEffect(recording) {
        if (recording) scrollY = maxScrollY()
    }

    val textMeasurer = rememberTextMeasurer()
    val rulerTextStyle = TextStyle(color = AppColors.RulerText, fontSize = 11.sp)

    Row(modifier.background(AppColors.Background)) {
        // ----- Cabeceras de pista -----
        Column(
            Modifier
                .width(HeaderWidth)
                .fillMaxHeight(),
        ) {
            Box(
                Modifier
                    .height(RulerHeight)
                    .fillMaxWidth()
                    .background(AppColors.Ruler)
                    .padding(start = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text("Pistas", color = AppColors.RulerText, fontSize = 12.sp)
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            scrollY = (scrollY - delta).coerceIn(0f, maxScrollY())
                        },
                    ),
            ) {
                Column(
                    Modifier
                        .wrapContentHeight(align = Alignment.Top, unbounded = true)
                        .offset { IntOffset(0, -scrollY.roundToInt()) },
                ) {
                    project.tracks.forEach { track ->
                        TrackHeader(
                            track = track,
                            selected = selection?.trackIds?.contains(track.id) == true,
                            onSelect = { onSelectTrack(track.id) },
                            onToggleMute = { onToggleMute(track.id) },
                            onToggleSolo = { onToggleSolo(track.id) },
                            onInstrument = { onInstrument(track) },
                            onRename = { onRename(track) },
                            onDelete = { onDelete(track) },
                        )
                    }
                    if (recording) RecordingHeader()
                }
            }
        }

        // ----- Línea de tiempo -----
        Box(Modifier.fillMaxSize()) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .onSizeChanged { viewSize = it }
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            if (!selecting) {
                                if (zoom != 1f) zoomBy(zoom, centroid.x)
                                scrollX = max(0f, scrollX - pan.x)
                                scrollY = (scrollY - pan.y).coerceIn(0f, maxScrollY())
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { offset -> currentOnSetCursor(tickAt(offset.x)) })
                    }
                    .pointerInput(Unit) {
                        var anchorTick = 0L
                        var anchorRow = 0
                        fun update(x: Float, y: Float) {
                            val tracks = currentProject.tracks
                            if (tracks.isEmpty()) return
                            val row = rowAt(y).coerceIn(0, tracks.lastIndex)
                            val ids = (min(anchorRow, row)..max(anchorRow, row)).map { tracks[it].id }.toSet()
                            val t = tickAt(x)
                            currentOnSelect(Selection(min(anchorTick, t), max(anchorTick, t), ids))
                        }
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                selecting = true
                                anchorTick = tickAt(offset.x)
                                anchorRow = rowAt(offset.y).coerceIn(0, max(0, currentProject.tracks.lastIndex))
                                update(offset.x, offset.y)
                            },
                            onDragEnd = { selecting = false },
                            onDragCancel = { selecting = false },
                            onDrag = { change, _ ->
                                change.consume()
                                update(change.position.x, change.position.y)
                            },
                        )
                    },
            ) {
                val w = size.width
                val h = size.height
                val firstTick = scrollX / pxPerTick
                val lastTick = (scrollX + w) / pxPerTick
                fun xOf(tick: Long): Float = tick * pxPerTick - scrollX
                fun rowTop(i: Int): Float = rulerPx + i * trackPx - scrollY

                val beatPx = PPQ * pxPerTick
                val barTicks = project.ticksPerBar
                var barStep = 1L
                while (barStep * barTicks * pxPerTick < 56.dp.toPx()) barStep *= 2

                clipRect(top = rulerPx) {
                    // Fondo de filas
                    for (i in 0 until rowCount) {
                        val top = rowTop(i)
                        if (top > h || top + trackPx < rulerPx) continue
                        val isRecRow = recording && i == project.tracks.size
                        val bg = when {
                            isRecRow -> AppColors.Record.copy(alpha = 0.10f)
                            i % 2 == 0 -> AppColors.TrackBg
                            else -> AppColors.TrackBgAlt
                        }
                        drawRect(bg, Offset(0f, top), Size(w, trackPx))
                    }
                    // Rejilla de compases y tiempos
                    if (beatPx >= 10f) {
                        var beat = floor(firstTick / PPQ).toLong()
                        while (beat * PPQ <= lastTick) {
                            val x = xOf(beat * PPQ)
                            val isBar = beat % project.beatsPerBar == 0L
                            drawLine(
                                if (isBar) AppColors.GridBar else AppColors.GridBeat,
                                Offset(x, rulerPx), Offset(x, h),
                            )
                            beat++
                        }
                    } else {
                        var bar = (floor(firstTick / barTicks).toLong() / barStep) * barStep
                        while (bar * barTicks <= lastTick) {
                            val x = xOf(bar * barTicks)
                            drawLine(AppColors.GridBar, Offset(x, rulerPx), Offset(x, h))
                            bar += barStep
                        }
                    }
                    // Selección
                    selection?.takeIf { !it.isEmpty }?.let { sel ->
                        project.tracks.forEachIndexed { i, track ->
                            if (track.id in sel.trackIds) {
                                val x0 = xOf(sel.startTick)
                                drawRect(
                                    AppColors.Selection,
                                    Offset(x0, rowTop(i)),
                                    Size(xOf(sel.endTick) - x0, trackPx),
                                )
                            }
                        }
                    }
                    // Notas
                    val audible = project.audibleTrackIds()
                    project.tracks.forEachIndexed { i, track ->
                        val top = rowTop(i)
                        if (top > h || top + trackPx < rulerPx) return@forEachIndexed
                        drawNotes(
                            notes = track.notes,
                            top = top,
                            rowHeight = trackPx,
                            color = AppColors.Tracks[track.colorIndex % AppColors.Tracks.size],
                            faded = track.id !in audible,
                            firstTick = firstTick,
                            lastTick = lastTick,
                            pxPerTick = pxPerTick,
                            scrollX = scrollX,
                        )
                    }
                    if (recording) {
                        drawNotes(
                            notes = recordingNotes,
                            top = rowTop(project.tracks.size),
                            rowHeight = trackPx,
                            color = AppColors.Record,
                            faded = false,
                            firstTick = firstTick,
                            lastTick = lastTick,
                            pxPerTick = pxPerTick,
                            scrollX = scrollX,
                        )
                    }
                    // Separadores
                    for (i in 1..rowCount) {
                        val y = rowTop(i)
                        drawLine(AppColors.Divider, Offset(0f, y), Offset(w, y), strokeWidth = 2f)
                    }
                }

                // Regla con números de compás
                drawRect(AppColors.Ruler, Offset.Zero, Size(w, rulerPx))
                if (beatPx >= 10f) {
                    var beat = floor(firstTick / PPQ).toLong()
                    while (beat * PPQ <= lastTick) {
                        val x = xOf(beat * PPQ)
                        drawLine(AppColors.GridBar, Offset(x, rulerPx * 0.7f), Offset(x, rulerPx))
                        beat++
                    }
                }
                var bar = (floor(firstTick / barTicks).toLong() / barStep) * barStep
                while (bar * barTicks <= lastTick) {
                    val x = xOf(bar * barTicks)
                    drawLine(AppColors.RulerText, Offset(x, rulerPx * 0.35f), Offset(x, rulerPx))
                    drawText(textMeasurer, "${bar + 1}", Offset(x + 4f, 2f), rulerTextStyle)
                    bar += barStep
                }
                drawLine(AppColors.Divider, Offset(0f, rulerPx), Offset(w, rulerPx), strokeWidth = 2f)

                // Cursor de edición
                if (selection == null || selection.isEmpty) {
                    val cx = xOf(cursorTick)
                    drawLine(AppColors.Cursor, Offset(cx, rulerPx), Offset(cx, h), strokeWidth = 1.5f)
                }
                // Cabezal de reproducción
                if (transport != TransportState.STOPPED) {
                    val px = xOf(positionState.value)
                    val color = if (recording) AppColors.Record else AppColors.Playhead
                    drawLine(color, Offset(px, 0f), Offset(px, h), strokeWidth = 2.dp.toPx())
                }
            }

            // Botones de zoom, para no depender solo del pellizco.
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmallFloatingActionButton(onClick = { zoomBy(1.5f, viewSize.width / 2f) }) {
                    Icon(Icons.Filled.ZoomIn, "Acercar")
                }
                SmallFloatingActionButton(onClick = { zoomBy(1 / 1.5f, viewSize.width / 2f) }) {
                    Icon(Icons.Filled.ZoomOut, "Alejar")
                }
                SmallFloatingActionButton(onClick = {
                    val end = max(project.endTick, project.ticksPerBar * 4)
                    if (viewSize.width > 0) {
                        pxPerTick = (viewSize.width * 0.95f / end).coerceIn(MIN_PX_PER_TICK, MAX_PX_PER_TICK)
                        scrollX = 0f
                    }
                }) {
                    Icon(Icons.Filled.FitScreen, "Ajustar a la pantalla")
                }
            }
        }
    }
}

private fun DrawScope.drawNotes(
    notes: List<Note>,
    top: Float,
    rowHeight: Float,
    color: Color,
    faded: Boolean,
    firstTick: Float,
    lastTick: Float,
    pxPerTick: Float,
    scrollX: Float,
) {
    if (notes.isEmpty()) return
    var lo = notes.minOf { it.pitch }
    var hi = notes.maxOf { it.pitch }
    if (hi - lo < 12) {
        val mid = (hi + lo) / 2
        lo = mid - 6
        hi = mid + 6
    }
    val pad = 6f
    val avail = rowHeight - 2 * pad
    val noteH = (avail / (hi - lo + 1)).coerceIn(2f, 10f)
    for (n in notes) {
        if (n.endTick < firstTick || n.startTick > lastTick) continue
        val x0 = n.startTick * pxPerTick - scrollX
        val x1 = max(x0 + 2f, n.endTick * pxPerTick - scrollX)
        val y = top + pad + (hi - n.pitch).toFloat() / (hi - lo) * (avail - noteH)
        val alpha = if (faded) 0.3f else 0.55f + 0.45f * n.velocity / 127f
        drawRect(color.copy(alpha = alpha), Offset(x0, y), Size(x1 - x0, noteH))
    }
}

@Composable
private fun TrackHeader(
    track: Track,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSolo: () -> Unit,
    onInstrument: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val color = AppColors.Tracks[track.colorIndex % AppColors.Tracks.size]
    Row(
        Modifier
            .width(HeaderWidth)
            .height(TrackHeight)
            .background(if (selected) AppColors.TrackHeaderSelected else AppColors.TrackHeader),
    ) {
        Box(
            Modifier
                .width(5.dp)
                .fillMaxHeight()
                .background(color),
        )
        Column(
            Modifier
                .fillMaxSize()
                .clickable(onClick = onSelect)
                .padding(start = 6.dp, top = 2.dp, end = 0.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    track.name,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.MoreVert, "Opciones de pista", tint = AppColors.RulerText)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Renombrar") }, onClick = {
                            menu = false
                            onRename()
                        })
                        DropdownMenuItem(text = { Text("Instrumento y canal") }, onClick = {
                            menu = false
                            onInstrument()
                        })
                        DropdownMenuItem(text = { Text("Eliminar pista") }, onClick = {
                            menu = false
                            onDelete()
                        })
                    }
                }
            }
            Text(
                track.program?.let { GM_INSTRUMENTS[it] } ?: "Canal ${track.channel + 1}",
                color = AppColors.Muted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ToggleChip("M", track.muted, Color(0xFFF59E0B), onToggleMute)
                ToggleChip("S", track.solo, Color(0xFF5B9CF5), onToggleSolo)
            }
        }
    }
}

@Composable
private fun ToggleChip(label: String, on: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 38.dp, height = 26.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (on) color else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (on) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun RecordingHeader() {
    Row(
        Modifier
            .width(HeaderWidth)
            .height(TrackHeight)
            .background(AppColors.Record.copy(alpha = 0.25f))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(AppColors.Record),
        )
        Spacer(Modifier.width(6.dp))
        Text("Grabando…", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
