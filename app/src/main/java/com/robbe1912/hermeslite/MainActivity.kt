package com.robbe1912.hermeslite

import android.content.Intent
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
        App.instance.openWake(intent)
        setContent {
            HermesLiteTheme {
                AppRoot()
            }
        }
    }

    /** The app is open, and the user taps a notification. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        App.instance.openWake(intent)
    }
}
