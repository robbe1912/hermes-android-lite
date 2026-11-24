package com.robbe1912.hermeslite.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.robbe1912.hermeslite.App
import com.robbe1912.hermeslite.gateway.GatewayException
import com.robbe1912.hermeslite.gateway.GatewayRpcError
import com.robbe1912.hermeslite.gateway.NotSetUpException
import com.robbe1912.hermeslite.tunnel.TunnelException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val TAG = "HermesLite"

/** Turns an error into one short sentence for the user. The log keeps the full error. */
internal fun userMessage(e: Throwable): String {
    Log.w(TAG, "A gateway call failed", e)
    return when (e) {
        is NotSetUpException -> "Open Settings and set up your gateway."
        is TunnelException -> e.message ?: "The tailnet is not available."
        is GatewayRpcError ->
            if (e.code == 4401) "The gateway closed the link. Open Settings and set up the gateway again."
            else e.message ?: "The gateway reported an error."
        is GatewayException -> e.message ?: "The gateway is not available."
        else -> "Unexpected error: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}"
    }
}

private val timeFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault())

/** Formats epoch seconds for a list row. An empty string for 0. */
internal fun formatTime(epochSeconds: Long): String {
    if (epochSeconds <= 0) return ""
    return runCatching { timeFormat.format(Instant.ofEpochSecond(epochSeconds)) }.getOrDefault("")
}

/** Shows [message] on the screen for a short time. A null message shows nothing. */
@Composable
internal fun SnackMessage(snackbar: SnackbarHostState, message: String?) {
    LaunchedEffect(message) {
        if (message != null) snackbar.showSnackbar(message)
    }
}

/** One line below the top bar. It shows a message and one action. */
@Composable
internal fun Banner(text: String, action: Pair<String, () -> Unit>? = null) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (action != null) {
            TextButton(onClick = action.second) { Text(action.first) }
        }
    }
}

/** The frame of a screen: a top bar, a back arrow, the message host, and the content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScreenScaffold(
    title: String,
    snackbar: SnackbarHostState,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = { actions() },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = { floatingActionButton() },
        bottomBar = { bottomBar() },
        content = content,
    )
}

/** One field of the setup form. A secret field hides its text. */
@Composable
internal fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
) {
    var shown by remember { mutableStateOf(false) }
    val hidden = secret && !shown
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
            capitalization = KeyboardCapitalization.None,
            autoCorrect = !secret,
        ),
        trailingIcon = if (secret) {
            {
                TextButton(onClick = { shown = !shown }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text(if (shown) "Hide" else "Show", softWrap = false, maxLines = 1)
                }
            }
        } else {
            null
        },
    )
}

/** Opens the link in the browser or in the Tailscale app. */
internal fun openLink(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        App.instance.startActivity(intent)
    } catch (e: Exception) {
        // The device has no app for links.
    }
}

/** Starts the Tailscale node and waits for the approval. The wait runs in the app scope. */
internal fun signInTailscale(onError: (String) -> Unit) {
    val app = App.instance
    app.appScope.launch { runCatching { app.tunnel.ensureRunning() }.onFailure { onError(userMessage(it)) } }
}

/** The roots of the app. Each root screen shows [RootBar] at the bottom. */
internal enum class RootTab(val label: String) {
    Channels("Threads"),
    Cron("Cron"),
}

/** The bar at the bottom of a root screen. A tap on a tab shows that root. */
@Composable
internal fun RootBar(selected: RootTab, onSelect: (RootTab) -> Unit) {
    NavigationBar {
        RootTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { if (tab != selected) onSelect(tab) },
                icon = {
                    Icon(
                        if (tab == RootTab.Channels) Icons.AutoMirrored.Filled.List else Icons.Default.DateRange,
                        contentDescription = null,
                    )
                },
                label = { Text(tab.label) },
            )
        }
    }
}

/** The version name of the app, for example "0.1.0". An empty string when Android does not tell. */
internal fun appVersion(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
}.getOrDefault("")
