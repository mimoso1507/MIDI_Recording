package com.midirecording.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Colores inspirados en el tema oscuro de Audacity. */
object AppColors {
    val Background = Color(0xFF1E2126)
    val Toolbar = Color(0xFF2A2E35)
    val TrackHeader = Color(0xFF2F343C)
    val TrackHeaderSelected = Color(0xFF3B4A5E)
    val TrackBg = Color(0xFF23272D)
    val TrackBgAlt = Color(0xFF262A31)
    val Ruler = Color(0xFF33383F)
    val RulerText = Color(0xFFC9CED6)
    val GridBar = Color(0x40FFFFFF)
    val GridBeat = Color(0x18FFFFFF)
    val Selection = Color(0x553D8BFF)
    val Cursor = Color(0xFFFFFFFF)
    val Playhead = Color(0xFF4ADE80)
    val Record = Color(0xFFE53E3E)
    val Divider = Color(0xFF14171B)
    val Muted = Color(0xFF8A9099)

    val Tracks = listOf(
        Color(0xFF5B9CF5), Color(0xFFF59E0B), Color(0xFF34D399), Color(0xFFF472B6),
        Color(0xFFA78BFA), Color(0xFFFB7185), Color(0xFF22D3EE), Color(0xFFA3E635),
    )
}

private val scheme = darkColorScheme(
    primary = Color(0xFF5B9CF5),
    secondary = Color(0xFF4ADE80),
    error = AppColors.Record,
    background = AppColors.Background,
    surface = AppColors.Toolbar,
)

@Composable
fun MidiRecordingTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
