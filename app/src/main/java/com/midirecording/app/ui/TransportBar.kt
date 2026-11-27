package com.midirecording.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AvTimer
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.midirecording.app.model.Project
import com.midirecording.app.model.TimeMath
import com.midirecording.app.model.noteName
import com.midirecording.app.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

@Composable
fun TransportBar(
    transport: TransportState,
    position: StateFlow<Long>,
    project: Project,
    connectedCount: Int,
    inputActivity: InputActivity?,
    onRecord: () -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onGoToStart: () -> Unit,
    onBpmChange: (Double) -> Unit,
    onBpmClick: () -> Unit,
    metronomeOn: Boolean,
    onMetronome: () -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onDevices: () -> Unit,
) {
    val recording = transport == TransportState.RECORDING
    val playing = transport == TransportState.PLAYING || recording
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Toolbar)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportButton(Icons.Filled.SkipPrevious, "Ir al inicio", onClick = onGoToStart)
        TransportButton(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            if (playing) "Pausa" else "Reproducir",
            tint = AppColors.Playhead,
            onClick = onPlayPause,
        )
        TransportButton(Icons.Filled.Stop, "Detener", onClick = onStop)
        TransportButton(
            Icons.Filled.FiberManualRecord,
            "Grabar",
            tint = AppColors.Record,
            highlighted = recording,
            onClick = onRecord,
        )
        Spacer(Modifier.width(8.dp))
        TimeDisplay(position, project, recording)
        Spacer(Modifier.width(8.dp))
        BpmControl(project.bpm, enabled = !playing, onBpmChange, onBpmClick)
        TransportButton(
            Icons.Filled.AvTimer,
            if (metronomeOn) "Apagar metrónomo" else "Encender metrónomo",
            tint = if (metronomeOn) Color(0xFFF59E0B) else AppColors.Muted,
            highlighted = metronomeOn,
            onClick = onMetronome,
        )
        TransportButton(
            Icons.AutoMirrored.Filled.Undo,
            "Deshacer",
            tint = if (canUndo && !recording) Color.White else AppColors.Muted,
            onClick = onUndo,
        )
        Spacer(Modifier.width(8.dp))
        InputIndicator(inputActivity)
        BadgedBox(badge = {
            if (connectedCount > 0) Badge { Text("$connectedCount") }
        }) {
            TransportButton(Icons.Filled.Piano, "Dispositivos MIDI", onClick = onDevices)
        }
    }
}

@Composable
private fun TransportButton(
    icon: ImageVector,
    description: String,
    tint: Color = Color.White,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (highlighted) tint.copy(alpha = 0.25f) else Color.Transparent),
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(30.dp))
    }
}

@Composable
private fun TimeDisplay(position: StateFlow<Long>, project: Project, recording: Boolean) {
    val tick by position.collectAsStateWithLifecycle()
    Column(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(horizontal = 10.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            TimeMath.formatTime(tick, project.bpm),
            color = if (recording) AppColors.Record else AppColors.Playhead,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
        Text(
            "Compás ${TimeMath.formatBarBeat(tick, project.beatsPerBar)}",
            color = AppColors.RulerText,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun BpmControl(bpm: Double, enabled: Boolean, onChange: (Double) -> Unit, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(-1.0) }, enabled = enabled, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Remove, "Bajar BPM", tint = Color.White)
        }
        Column(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(formatBpm(bpm), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("BPM", color = AppColors.Muted, fontSize = 10.sp)
        }
        IconButton(onClick = { onChange(1.0) }, enabled = enabled, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Add, "Subir BPM", tint = Color.White)
        }
    }
}

/** Piloto que se enciende con cada nota recibida, con el nombre del dispositivo. */
@Composable
private fun InputIndicator(activity: InputActivity?) {
    var lit by remember { mutableStateOf(false) }
    LaunchedEffect(activity) {
        if (activity != null) {
            lit = true
            delay(150)
            lit = false
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(end = 4.dp),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (lit) AppColors.Playhead else Color.DarkGray),
        )
        Column {
            Text(activity?.deviceName ?: "Entrada MIDI", color = AppColors.RulerText, fontSize = 11.sp, maxLines = 1)
            Text(
                activity?.let { noteName(it.note) } ?: "—",
                color = AppColors.Muted,
                fontSize = 11.sp,
                modifier = Modifier.height(14.dp),
            )
        }
    }
}
