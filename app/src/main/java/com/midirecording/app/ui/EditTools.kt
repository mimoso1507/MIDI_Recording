package com.midirecording.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midirecording.app.engine.QuantizeGrid
import com.midirecording.app.ui.theme.AppColors

/** Barra que aparece al seleccionar algo, con las acciones de edición. */
@Composable
fun SelectionBar(
    selection: Selection,
    onQuantize: () -> Unit,
    onGroup: () -> Unit,
    onDeleteNotes: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .padding(start = 8.dp, bottom = 12.dp, end = 72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.Toolbar)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionButton(Icons.Filled.GridOn, "Cuantizar", onQuantize)
        if (selection.trackIds.size >= 2) {
            ActionButton(Icons.Filled.Layers, "Agrupar", onGroup)
        }
        ActionButton(Icons.Filled.Delete, "Borrar notas", onDeleteNotes)
        IconButton(onClick = onClear) {
            Icon(Icons.Filled.Close, "Quitar selección", tint = AppColors.RulerText)
        }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(icon, null, tint = Color.White)
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuantizeDialog(
    title: String,
    onConfirm: (QuantizeGrid, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var grid by remember { mutableStateOf(QuantizeGrid.SIXTEENTH) }
    var ends by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(grid, ends) }) { Text("Cuantizar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        title = { Text(title) },
        text = {
            Column {
                Text("Ajustar el inicio de cada nota a:", fontSize = 14.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuantizeGrid.entries.forEach { g ->
                        FilterChip(selected = grid == g, onClick = { grid = g }, label = { Text(g.label) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = ends, onCheckedChange = { ends = it })
                    Text("Ajustar también el final de las notas", fontSize = 14.sp)
                }
                Text("Si no te gusta, pulsa Deshacer.", color = AppColors.Muted, fontSize = 12.sp)
            }
        },
    )
}

/** Menú «Archivo»: nuevo, importar/exportar MIDI, importar audio y exportar la mezcla. */
@Composable
fun FileMenu(
    onNew: () -> Unit,
    onImportMidi: () -> Unit,
    onExportMidi: () -> Unit,
    onImportAudio: () -> Unit,
    onExportWav: () -> Unit,
    onExportMp3: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.FolderOpen, "Archivo", tint = Color.White)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Nuevo proyecto") }, onClick = {
                open = false
                onNew()
            })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Importar MIDI (.mid)…") }, onClick = {
                open = false
                onImportMidi()
            })
            DropdownMenuItem(text = { Text("Exportar MIDI (.mid)…") }, onClick = {
                open = false
                onExportMidi()
            })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Importar audio para escuchar (WAV/MP3)…") }, onClick = {
                open = false
                onImportAudio()
            })
            DropdownMenuItem(text = { Text("Exportar mezcla a WAV…") }, onClick = {
                open = false
                onExportWav()
            })
            DropdownMenuItem(text = { Text("Exportar mezcla a MP3…") }, onClick = {
                open = false
                onExportMp3()
            })
        }
    }
}
