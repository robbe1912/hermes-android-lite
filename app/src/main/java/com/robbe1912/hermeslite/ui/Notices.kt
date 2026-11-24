package com.robbe1912.hermeslite.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** The version of the app, and the button for the notices of the open-source parts. */
@Composable
internal fun AboutRow() {
    val context = LocalContext.current
    val version = remember { appVersion(context) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        NoticesButton()
        Spacer(Modifier.weight(1f))
        Text(
            "Hermes Lite $version",
            Modifier.padding(end = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Shows the NOTICE file of the repository. The Gradle build puts it into the app. */
@Composable
private fun NoticesButton() {
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val text by produceState("The notices did not load.") {
        value = runCatching { context.assets.open("NOTICE").bufferedReader().use { it.readText() } }
            .getOrElse { "The notices did not load: ${it.message}" }
    }
    TextButton(onClick = { open = true }) { Text("Open source notices") }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
            title = { Text("Notices") },
            text = {
                SelectionContainer {
                    Text(
                        text,
                        Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
        )
    }
}
