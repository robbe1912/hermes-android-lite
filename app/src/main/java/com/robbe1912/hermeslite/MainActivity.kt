package com.robbe1912.hermeslite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.robbe1912.hermeslite.ui.AppRoot
import com.robbe1912.hermeslite.ui.theme.HermesLiteTheme

/** The only activity of the app. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HermesLiteTheme {
                AppRoot()
            }
        }
    }
}
