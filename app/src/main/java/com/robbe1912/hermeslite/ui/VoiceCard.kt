package com.robbe1912.hermeslite.ui

import android.speech.tts.Voice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robbe1912.hermeslite.App
import kotlinx.coroutines.launch
import java.util.Locale

/** Settings for reading assistant replies with the device's text-to-speech engine. */
@Composable
internal fun VoiceCard() {
    val app = App.instance
    val scope = rememberCoroutineScope()
    val on by app.settings.readAloud.collectAsStateWithLifecycle()
    val selectedVoice by app.settings.ttsVoice.collectAsStateWithLifecycle()
    val voices by app.readAloud.voices.collectAsStateWithLifecycle()
    val voicesLoaded by app.readAloud.voicesLoaded.collectAsStateWithLifecycle()
    val engineError by app.readAloud.voiceEngineError.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { app.readAloud.prepare() }

    fun chooseVoice(name: String?) {
        scope.launch {
            app.settings.saveTtsVoice(name)
            app.readAloud.selectVoice(name)
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Read assistant replies aloud", style = MaterialTheme.typography.bodyLarge)
                Hint("Uses the voice selected below.")
            }
            Switch(checked = on, onCheckedChange = { value ->
                scope.launch { app.settings.saveReadAloud(value) }
            })
        }
        val chosen = voices.firstOrNull { it.name == selectedVoice }
        Text(
            chosen?.let { "${it.locale.getDisplayName(Locale.ENGLISH)} · ${it.name}" }
                ?: if (selectedVoice == null) "Device default voice" else "Saved voice is not available",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { showPicker = true }) { Text("Choose English voice") }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Choose an English voice") },
            text = {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (!voicesLoaded) {
                        CircularProgressIndicator()
                    } else {
                        engineError?.let { Hint(it) }
                        VoiceChoiceRow(
                            title = "Device default",
                            detail = "Uses the phone's default language.",
                            selected = selectedVoice == null,
                            onClick = { chooseVoice(null) },
                        )
                        if (voices.isEmpty()) {
                            Hint("No English voices are available from the phone's text-to-speech engine.")
                        }
                        voices.forEach { voice ->
                            VoiceChoiceRow(
                                title = voice.locale.getDisplayName(Locale.ENGLISH),
                                detail = "${voice.name} · ${qualityLabel(voice.quality)} · ${
                                    if (voice.isNetworkConnectionRequired) "Online" else "On device"
                                }",
                                selected = selectedVoice == voice.name,
                                onClick = { chooseVoice(voice.name) },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = app.readAloud::preview, enabled = voicesLoaded) { Text("Preview") }
                    TextButton(onClick = { showPicker = false }) { Text("Done") }
                }
            },
        )
    }
}

@Composable
private fun VoiceChoiceRow(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun qualityLabel(quality: Int): String = when {
    quality >= Voice.QUALITY_VERY_HIGH -> "Very high quality"
    quality >= Voice.QUALITY_HIGH -> "High quality"
    quality >= Voice.QUALITY_NORMAL -> "Normal quality"
    quality >= Voice.QUALITY_LOW -> "Low quality"
    quality >= Voice.QUALITY_VERY_LOW -> "Very low quality"
    else -> "Quality not reported"
}
