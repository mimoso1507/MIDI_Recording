package com.midirecording.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.midirecording.app.ui.MainScreen
import com.midirecording.app.ui.MainViewModel
import com.midirecording.app.ui.theme.MidiRecordingTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // La pantalla no se apaga mientras se graba o se toca.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MidiRecordingTheme {
                MainScreen(viewModel)
            }
        }
    }
}
