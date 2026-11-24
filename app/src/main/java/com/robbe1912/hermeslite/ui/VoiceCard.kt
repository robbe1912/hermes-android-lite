package com.robbe1912.hermeslite.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robbe1912.hermeslite.App
import kotlinx.coroutines.launch

/** The read aloud setting. The voice of the device reads each finished answer. */
@Composable
internal fun VoiceCard() {
    val app = App.instance
    val scope = rememberCoroutineScope()
    val on by app.settings.readAloud.collectAsStateWithLifecycle()

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Heading("Voice")
            Hint("The voice of this device reads each finished answer.")
        }
        Switch(checked = on, onCheckedChange = { value -> scope.launch { app.settings.saveReadAloud(value) } })
    }
}
