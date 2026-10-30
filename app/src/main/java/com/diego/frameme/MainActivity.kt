package com.diego.frameme

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.diego.frameme.ar.ARScreen
import com.diego.frameme.ui.ArAvailabilityGate
import com.diego.frameme.ui.theme.FrameMeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FrameMeTheme {
                ArAvailabilityGate {
                    ARScreen()
                }
            }
        }
    }
}
