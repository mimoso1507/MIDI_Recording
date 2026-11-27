package com.midirecording.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.midirecording.app.midi.ControlAction
import com.midirecording.app.ui.theme.AppColors

/**
 * Lista de acciones con su botón asignado. "Aprender" espera a que pulses
 * un botón, tecla o pad del controlador y lo asigna a esa acción.
 */
@Composable
fun ControlsDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val bindings by vm.controlBindings.collectAsStateWithLifecycle()
    val learning by vm.learning.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = {
            vm.learn(null)
            onDismiss()
        },
        confirmButton = {
            TextButton(onClick = {
                vm.learn(null)
                onDismiss()
            }) { Text("Listo") }
        },
        title = { Text("Botones del controlador") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Pulsa «Aprender» y luego el botón, pad o tecla de tu dispositivo MIDI que quieras usar. " +
                        "Funciona con USB y Bluetooth. Esos botones ya no se graban como notas.",
                    color = AppColors.Muted,
                    fontSize = 13.sp,
                )
                ControlAction.entries.forEach { action ->
                    val trigger = bindings[action]
                    val waiting = learning == action
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (waiting) AppColors.Playhead.copy(alpha = 0.15f) else Color.Transparent)
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(action.label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(
                                when {
                                    waiting -> "Pulsa un botón del controlador…"
                                    trigger != null -> trigger.describe()
                                    else -> "Sin asignar"
                                },
                                color = if (waiting) AppColors.Playhead else AppColors.Muted,
                                fontSize = 12.sp,
                            )
                        }
                        if (waiting) {
                            OutlinedButton(onClick = { vm.learn(null) }) { Text("Cancelar") }
                        } else {
                            Button(
                                onClick = { vm.learn(action) },
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.TrackHeaderSelected),
                            ) { Text("Aprender", color = Color.White) }
                        }
                        if (trigger != null && !waiting) {
                            IconButton(onClick = { vm.clearBinding(action) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Filled.Close, "Quitar asignación", tint = AppColors.Muted)
                            }
                        }
                    }
                }
            }
        },
    )
}
