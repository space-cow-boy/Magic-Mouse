package com.magicmouse.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.magicmouse.android.ui.MainScreen
import com.magicmouse.android.ui.theme.MagicMouseTheme
import androidx.compose.material3.Surface

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Keep screen on while acting as mouse
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MagicMouseTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Sensor reading is managed by ViewModel scope — continues if app is backgrounded
        // Optional: you could stop here to save battery when not in focus
    }

    override fun onDestroy() {
        super.onDestroy()
        // ViewModel.onCleared() will stop sensors
    }
}
