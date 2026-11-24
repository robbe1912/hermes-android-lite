package com.robbe1912.hermeslite.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robbe1912.hermeslite.App
import com.robbe1912.hermeslite.data.RouteMode
import com.robbe1912.hermeslite.gateway.ChannelRow
import com.robbe1912.hermeslite.tunnel.TunnelState
import kotlinx.coroutines.launch

/** The channel list: the threads of the gateway, newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChannelsScreen(
    snackbar: SnackbarHostState,
    tick: Int,
    onOpen: (ChannelRow) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
    onTab: (RootTab) -> Unit,
) {
    val app = App.instance
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<ChannelRow>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    val onTailnet by app.api.onTailnet.collectAsStateWithLifecycle()
    val tunnel by app.tunnel.state.collectAsStateWithLifecycle()
    val config by app.settings.server.collectAsStateWithLifecycle()

    fun load() {
        scope.launch {
            runCatching { app.api.channels() }
                .onSuccess {
                    rows = it
                    failed = null
                }
                .onFailure { failed = userMessage(it) }
            loading = false
            refreshing = false
        }
    }

    // The first load and each wake-up read the list.
    LaunchedEffect(Unit) { load() }
    LaunchedEffect(tick) { if (tick > 0) load() }

    ScreenScaffold(
        title = "Hermes Lite",
        snackbar = snackbar,
        actions = {
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New thread") },
            )
        },
        bottomBar = { RootBar(RootTab.Channels, onTab) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SnackMessage(snackbar, failed)
            if (onTailnet) Banner("You see the gateway through Tailscale.")
            if (config?.routeMode != RouteMode.Direct) TunnelBanner(tunnel)
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                rows == null -> Message("The channel list did not load.", failed.orEmpty(), "Retry" to ::load)
                rows!!.isEmpty() -> Message("No threads yet.", "Start a new thread with the button.", null)
                else -> PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; load() }) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(rows!!, key = { it.id }) { row ->
                            ChannelItem(row, onOpen)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/** Tells the user when the Tailscale node needs action. Other states need no line. */
@Composable
internal fun TunnelBanner(state: TunnelState) {
    when (state) {
        is TunnelState.NeedsApproval -> Banner(
            text = "Tailscale: approve this device once.",
            action = if (state.url.isNotBlank()) "Open link" to { openLink(state.url) } else null,
        )
        is TunnelState.Failed -> Banner(state.message, "Retry" to { signInTailscale { } })
        else -> Unit
    }
}

/** One thread row: title, the last text, and the time. */
@Composable
private fun ChannelItem(row: ChannelRow, onOpen: (ChannelRow) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(row) }.padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (row.preview.isNotBlank()) {
                Text(
                    row.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
        Text(
            formatTime(row.lastActivityAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A message in the middle of the screen, with one button. */
@Composable
internal fun Message(title: String, text: String, action: Pair<String, () -> Unit>?) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null) {
            TextButton(onClick = action.second) { Text(action.first) }
        }
    }
}
