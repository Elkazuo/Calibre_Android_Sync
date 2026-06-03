package com.example.calibredrive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.calibredrive.ui.theme.CalibreDriveTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CalibreDriveTheme {
                CalibreReaderApp()
            }
        }
    }
}
