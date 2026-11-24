package com.robbe1912.hermeslite.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
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
import com.robbe1912.hermeslite.data.AuthMode
import com.robbe1912.hermeslite.data.RouteMode
import com.robbe1912.hermeslite.data.ServerConfig
import com.robbe1912.hermeslite.gateway.str
import com.robbe1912.hermeslite.tunnel.TunnelState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The gateway form. It is the first screen of the app, and it is the Settings screen later.
 *
 * "Save and test" calls the gateway with the form values first. The values are stored only when
 * that call works.
 */
@Composable
internal fun SetupScreen(modifier: Modifier, onSaved: () -> Unit, onError: (String) -> Unit) {
    val app = App.instance
    val scope = rememberCoroutineScope()
    val saved = app.settings.server.value ?: ServerConfig()
    var address by remember { mutableStateOf(saved.address) }
    var https by remember { mutableStateOf(saved.https) }
    var mode by remember { mutableStateOf(saved.authMode) }
    var user by remember { mutableStateOf(saved.username) }
    var secret by remember { mutableStateOf("") }
    var route by remember { mutableStateOf(saved.routeMode) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }

    fun save() {
        scope.launch {
            busy = true
            val config = ServerConfig(address.trim(), https, mode, user.trim(), secret.ifBlank { saved.secret }, route)
            val outcome = runCatching { app.api.probe(config) }
            busy = false
            outcome.fold(
                onSuccess = {
                    failed = null
                    app.settings.save(config)
                    app.api.reset()
                    // Start the sign-in for Tailscale now. Its link shows on the channel list.
                    if (route != RouteMode.Direct) signInTailscale { }
                    onSaved()
                },
                onFailure = { failed = userMessage(it) },
            )
        }
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Heading("Gateway")
        Field("Address (host:port)", address, { address = it })
        SwitchRow("Use HTTPS", https) { https = it }

        Heading("Sign in")
        Choice(listOf(AuthMode.Password to "Password", AuthMode.Token to "Token"), mode) { mode = it }
        if (mode == AuthMode.Password) {
            Field("User name", user, { user = it })
        }
        Field(if (mode == AuthMode.Password) "Password" else "Dashboard token", secret, { secret = it }, secret = true)
        if (secret.isBlank() && saved.secret.isNotBlank()) {
            Hint("The saved ${if (mode == AuthMode.Password) "password" else "token"} stays in use.")
        }

        Heading("Route")
        Choice(listOf(RouteMode.Auto to "Auto", RouteMode.Direct to "LAN only", RouteMode.Tailnet to "Tailscale"), route) { route = it }
        Hint(
            when (route) {
                RouteMode.Auto -> "The app uses your LAN at home. Away from home it uses Tailscale in the app."
                RouteMode.Direct -> "The app talks to the gateway on your LAN only."
                RouteMode.Tailnet -> "The app always goes through Tailscale in the app."
            },
        )
        if (route != RouteMode.Direct) {
            TailnetCard(onError)
        }

        Button(onClick = ::save, enabled = !busy && address.isNotBlank() && (secret.isNotBlank() || saved.secret.isNotBlank())) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Save and test")
        }
        failed?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

/** The gateway form with a back arrow and a way to forget the gateway. */
@Composable
internal fun SettingsScreen(snackbar: SnackbarHostState, onDone: () -> Unit, onError: (String) -> Unit) {
    val app = App.instance
    var confirm by remember { mutableStateOf(false) }
    ScreenScaffold(title = "Settings", snackbar = snackbar, onBack = onDone) { padding ->
        Column(Modifier.padding(padding)) {
            SetupScreen(Modifier.weight(1f), onSaved = onDone, onError = onError)
            HorizontalDivider()
            PushCard(onError)
            if (confirm) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = { app.appScope.launch {
                        app.api.reset()
                        app.settings.clear()
                    } }) { Text("Yes, forget it") }
                    TextButton(onClick = { confirm = false }) { Text("Keep") }
                }
            } else {
                TextButton(onClick = { confirm = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Forget this gateway")
                }
            }
        }
    }
}

/** The state of the Tailscale node of the app, with the sign-in and the sign-out. */
@Composable
private fun TailnetCard(onError: (String) -> Unit) {
    val app = App.instance
    val state by app.tunnel.state.collectAsStateWithLifecycle()
    var showLog by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }
    LaunchedEffect(showLog) {
        while (showLog) {
            log = runCatching { app.tunnel.logs() }.getOrDefault("")
            delay(2_000)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Heading("Tailscale in the app")
        Hint("Sign in once. Then the app reaches your gateway away from home, with no VPN app.")
        when (val current = state) {
            is TunnelState.Running -> {
                Hint("This device is in your tailnet: ${current.addresses.joinToString(" ")}")
                TextButton(onClick = { app.appScope.launch { app.tunnel.logout() } }) {
                    Text("Remove this device from the tailnet")
                }
            }
            is TunnelState.NeedsApproval -> {
                Hint("Approve this device in your Tailscale account:")
                Text(
                    current.url.ifBlank { "Waiting for the sign-in link." },
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(enabled = current.url.isNotBlank()) { openLink(current.url) },
                )
                TextButton(onClick = { app.appScope.launch { app.tunnel.stop() } }) { Text("Later") }
            }
            is TunnelState.Failed -> {
                Hint(current.message)
                SignInButton(onError)
            }
            TunnelState.Starting -> LinearProgressIndicator(Modifier.fillMaxWidth())
            TunnelState.Stopped, TunnelState.NeedsLogin -> SignInButton(onError)
        }
        if (state !is TunnelState.Stopped) {
            TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide log" else "Show log") }
        }
        if (showLog) Text(log, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SignInButton(onError: (String) -> Unit) {
    Button(onClick = { signInTailscale(onError) }) {
        Text("Sign in to Tailscale")
    }
}

@Composable
internal fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
internal fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange)
        Text(label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

/** One row of buttons. One of them is selected. */
@Composable
private fun <T> Choice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(label)
            }
        }
    }
}
