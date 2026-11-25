package com.robbe1912.hermeslite.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.robbe1912.hermeslite.App
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One thread: the rows, the card of a request, and the composer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThreadScreen(
    model: ThreadModel,
    title: String,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val loading by model.loading.collectAsStateWithLifecycle()
    val text by model.input.collectAsStateWithLifecycle()
    val modelChoices by model.availableModels.collectAsStateWithLifecycle()
    val selectedModel by model.selectedModel.collectAsStateWithLifecycle()
    val modelsLoading by model.modelsLoading.collectAsStateWithLifecycle()
    val modelError by model.modelError.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // The voice button: the microphone permission, the words, and the turn of the recognizer.
    val app = App.instance
    val context = LocalContext.current
    val listening by app.voice.listening.collectAsStateWithLifecycle()
    val heard by app.voice.text.collectAsStateWithLifecycle()
    val voiceError by app.voice.error.collectAsStateWithLifecycle()
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) app.voice.start()
    }
    LaunchedEffect(voiceError) {
        val message = voiceError ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        // Clear the message after the snackbar. Before that, the new key ends the snackbar.
        app.voice.error.value = null
    }
    LaunchedEffect(listening) {
        // A turn that ended with words puts them in the composer.
        if (!listening && heard.isNotBlank()) {
            app.voice.text.value = ""
            model.input.value = listOf(model.input.value.trim(), heard).filter { it.isNotBlank() }.joinToString(" ")
        }
    }
    DisposableEffect(Unit) {
        onDispose { app.voice.cancel() }
    }

    // The list follows the stream while the user is at the end.
    var follow by remember { mutableStateOf(true) }
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // A drag down reads older rows. A drag up at the end follows the stream again.
                if (available.y > 0) follow = false else if (!listState.canScrollForward) follow = true
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(Unit) { model.open(onCreated) }

    val lastRow = state.rows.lastOrNull()
    val streamLength = (lastRow as? Row.Message)?.text?.length ?: 0
    val waiting = state.streaming && (lastRow as? Row.Message)?.fromUser == true
    val showThinking = state.thinking.isNotBlank() || waiting
    val showPending = state.pending != null
    LaunchedEffect(state.rows.size, streamLength, showThinking, showPending, follow) {
        if (follow && state.rows.isNotEmpty()) {
            listState.scrollToItem(state.rows.lastIndex + (if (showThinking) 1 else 0) + (if (showPending) 1 else 0))
        }
    }

    val heading = state.title.ifBlank { title.ifBlank { "Thread" } }
    ScreenScaffold(title = heading, snackbar = snackbar, onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SnackMessage(snackbar, state.error)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().nestedScroll(connection),
            ) {
                itemsIndexed(state.rows) { _, row -> RowView(row) }
                if (showThinking) item { ThinkingRow(state.thinking.ifBlank { "Working" }) }
                if (showPending) item { PendingCard(state.pending!!, model) }
            }
            ModelPicker(
                choices = modelChoices,
                selected = selectedModel,
                loading = modelsLoading,
                error = modelError,
                enabled = !state.streaming && !showPending,
                onSelect = model::selectModel,
                onRefresh = model::refreshModels,
            )
            Composer(
                text = text,
                onText = { model.input.value = it },
                streaming = state.streaming,
                enabled = !showPending,
                listening = listening,
                heard = heard,
                onVoice = {
                    when {
                        app.voice.listening.value -> app.voice.finish()
                        !app.voice.available -> app.voice.start()
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED -> app.voice.start()
                        else -> mic.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onSend = { model.send(onCreated) },
                onStop = model::stop,
            )
        }
    }
}

@Composable
private fun RowView(row: Row) {
    when (row) {
        is Row.Message -> if (row.fromUser) UserBubble(row.text) else AssistantText(row.text, row.reasoning)
        is Row.Tool -> ToolLine(row)
    }
}

/** A user message that is longer starts folded. A cron run starts with a long prompt. */
private const val longMessage = 800

@Composable
private fun UserBubble(text: String) {
    var open by remember(text) { mutableStateOf(text.length <= longMessage) }
    val folded = text.length > longMessage && !open
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (folded) text.take(longMessage) else text,
                Modifier.background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.large).padding(12.dp),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = if (folded) 12 else Int.MAX_VALUE,
            )
            if (text.length > longMessage) {
                TextButton(onClick = { open = !open }) { Text(if (open) "Show less" else "Show all") }
            }
        }
    }
}

@Composable
private fun AssistantText(text: String, reasoning: String) {
    var showReasoning by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (reasoning.isNotBlank()) {
            TextButton(onClick = { showReasoning = !showReasoning }, contentPadding = PaddingValues(0.dp)) {
                Text("Show thinking", style = MaterialTheme.typography.labelMedium)
            }
            if (showReasoning) {
                Text(
                    reasoning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SelectionContainer {
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** The status line of the agent while it works. */
@Composable
private fun ThinkingRow(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}

/** One tool call. The line grows with the tool progress. */
@Composable
private fun ToolLine(tool: Row.Tool) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!tool.done) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
        val line = buildString {
            append(tool.name)
            if (tool.detail.isNotBlank()) {
                append(": ")
                append(tool.detail.take(200))
            }
            if (!tool.done) append(" ...")
        }
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}

/** A request of the gateway that waits for the user. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PendingCard(pending: PendingRequest, model: ThreadModel) {
    Column(
        Modifier.fillMaxWidth().padding(12.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (pending) {
            is PendingRequest.Approval -> {
                Text("The agent asks for approval", style = MaterialTheme.typography.titleSmall)
                if (pending.command.isNotBlank()) {
                    Text(
                        pending.command,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 6,
                    )
                }
                if (pending.description.isNotBlank()) {
                    Text(pending.description, style = MaterialTheme.typography.bodyMedium)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pending.choices.forEach { choice ->
                        Button(onClick = { model.answer(buildJsonObject { put("choice", choice) }) }) {
                            Text(choiceLabel(choice))
                        }
                    }
                }
            }
            is PendingRequest.Clarify -> ClarifyForm(pending, model)
        }
    }
}

/** The questions of the agent. Each answer is the typed text, or the selected choices. */
@Composable
private fun ClarifyForm(pending: PendingRequest.Clarify, model: ThreadModel) {
    val typed = remember(pending.id) { mutableStateMapOf<String, String>() }
    val picked = remember(pending.id) { mutableStateMapOf<String, Set<String>>() }

    fun answerOf(question: ClarifyQuestion): String =
        typed[question.id]?.trim()?.takeIf { it.isNotEmpty() }
            ?: picked[question.id].orEmpty().joinToString(", ")

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("The agent has a question", style = MaterialTheme.typography.titleSmall)
        pending.questions.forEach { question ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(question.text, style = MaterialTheme.typography.bodyMedium)
                if (question.choices.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        question.choices.forEach { choice ->
                            val on = choice in picked[question.id].orEmpty()
                            FilterChip(
                                selected = on,
                                onClick = {
                                    val now = picked[question.id].orEmpty()
                                    picked[question.id] = when {
                                        !question.multiSelect -> if (on) emptySet() else setOf(choice)
                                        on -> now - choice
                                        else -> now + choice
                                    }
                                },
                                label = { Text(choice) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = typed[question.id].orEmpty(),
                    onValueChange = { typed[question.id] = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Your answer") },
                    maxLines = 3,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    model.answer(
                        buildJsonObject {
                            put(
                                "answers",
                                buildJsonObject { pending.questions.forEach { question -> put(question.id, answerOf(question)) } },
                            )
                            put("outcome", "submitted")
                        },
                    )
                },
            ) {
                Text("Send answers")
            }
            TextButton(onClick = model::decline) { Text("Skip") }
        }
    }
}

@Composable
private fun ModelPicker(
    choices: List<ThreadModelOption>,
    selected: ThreadModelOption?,
    loading: Boolean,
    error: String?,
    enabled: Boolean,
    onSelect: (ThreadModelOption) -> Unit,
    onRefresh: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val filtered = choices.filter {
        it.model.contains(query, ignoreCase = true) ||
            it.providerName.contains(query, ignoreCase = true) ||
            it.provider.contains(query, ignoreCase = true)
    }
    TextButton(
        onClick = { open = true },
        enabled = enabled && !loading,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            selected?.let { "Model: ${it.providerName} · ${it.model}" }
                ?: if (loading) "Loading models…" else "Choose model",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (error != null) {
        Text(
            error,
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Choose model") },
            text = {
                Column(Modifier.heightIn(max = 480.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Search models") },
                        singleLine = true,
                    )
                    if (error != null) {
                        Text(
                            error,
                            Modifier.padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onRefresh, enabled = !loading) { Text("Retry") }
                    }
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(filtered, key = { "${it.provider}:${it.model}" }) { option ->
                            TextButton(
                                onClick = {
                                    onSelect(option)
                                    open = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(option.model, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            option.providerName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (option == selected) Text("Selected", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text("Close") }
            },
        )
    }
}

/** The text field and the send button. The send button becomes a stop button during a turn. */
@Composable
private fun Composer(
    text: String,
    onText: (String) -> Unit,
    streaming: Boolean,
    enabled: Boolean,
    listening: Boolean,
    heard: String,
    onVoice: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
        if (listening) {
            // The words that the recognizer heard until now.
            Text(
                heard.ifBlank { "Listening" },
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (!streaming) {
                TextButton(onClick = onVoice, enabled = enabled) { Text(if (listening) "Done" else "Voice") }
            }
            OutlinedTextField(
                value = text,
                onValueChange = onText,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                maxLines = 6,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            if (streaming) {
                TextButton(onClick = onStop) { Text("Stop") }
            } else {
                IconButton(onClick = onSend, enabled = enabled && text.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

private fun choiceLabel(choice: String): String = when (choice) {
    "once" -> "Allow once"
    "session" -> "Allow in this session"
    "always" -> "Always allow"
    "deny" -> "Deny"
    else -> choice
}
