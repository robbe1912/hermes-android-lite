package com.robbe1912.hermeslite.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robbe1912.hermeslite.App
import com.robbe1912.hermeslite.data.RouteMode
import com.robbe1912.hermeslite.gateway.CronJob
import com.robbe1912.hermeslite.gateway.CronRun
import com.robbe1912.hermeslite.gateway.toCronJob
import com.robbe1912.hermeslite.gateway.toCronRun
import kotlinx.coroutines.launch

/** The cron jobs of the gateway. */
@Composable
internal fun CronScreen(
    snackbar: SnackbarHostState,
    tick: Int,
    onOpen: (CronJob) -> Unit,
    onSettings: () -> Unit,
    onTab: (RootTab) -> Unit,
) {
    val app = App.instance
    val scope = rememberCoroutineScope()
    var jobs by remember { mutableStateOf<List<CronJob>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    val tunnel by app.tunnel.state.collectAsStateWithLifecycle()
    val config by app.settings.server.collectAsStateWithLifecycle()

    fun load() {
        scope.launch {
            runCatching { app.api.cronJobs().map { it.toCronJob() }.sortedBy { it.name.lowercase() } }
                .onSuccess {
                    jobs = it
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
        title = "Cron",
        snackbar = snackbar,
        actions = {
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        },
        bottomBar = { RootBar(RootTab.Cron, onTab) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SnackMessage(snackbar, failed)
            if (config?.routeMode != RouteMode.Direct) TunnelBanner(tunnel)
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                jobs == null -> Message("The cron list did not load.", failed.orEmpty(), "Retry" to ::load)
                jobs!!.isEmpty() -> Message("No cron jobs.", "The gateway has no cron jobs.", null)
                else -> PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; load() }) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(jobs!!, key = { it.id }) { job ->
                            JobItem(job, onOpen)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/** One job row: the name, the status, the schedule, and the times of the last and the next run. */
@Composable
private fun JobItem(job: CronJob, onOpen: (CronJob) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onOpen(job) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                job.name,
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            JobStatus(job)
        }
        val facts = buildList {
            add(job.schedule)
            if (!job.enabled) add("Paused")
            if (job.scriptOnly) add("Script without agent")
        }.joinToString("  ·  ")
        Text(facts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val times = buildList {
            if (job.lastRunAt > 0) add("Last run ${formatTime(job.lastRunAt)}")
            if (job.enabled && job.nextRunAt > 0) add("Next run ${formatTime(job.nextRunAt)}")
        }.joinToString("  ·  ")
        if (times.isNotEmpty()) {
            Text(times, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "Running" while a run is active, else the status of the last run. */
@Composable
private fun JobStatus(job: CronJob) {
    val label = MaterialTheme.typography.labelMedium
    when {
        job.running -> RunningLabel()
        job.lastStatus == "error" -> Text("Error", style = label, color = MaterialTheme.colorScheme.error)
        job.lastStatus == "ok" -> Text("OK", style = label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The runs of one cron job, newest first. A run is a thread or a report. */
@Composable
internal fun CronRunsScreen(
    jobId: String,
    title: String,
    snackbar: SnackbarHostState,
    tick: Int,
    onBack: () -> Unit,
    onOpenThread: (CronRun) -> Unit,
    onOpenReport: (CronRun) -> Unit,
) {
    val app = App.instance
    val scope = rememberCoroutineScope()
    var runs by remember(jobId) { mutableStateOf<List<CronRun>?>(null) }
    var loading by remember(jobId) { mutableStateOf(true) }
    var refreshing by remember(jobId) { mutableStateOf(false) }
    var failed by remember(jobId) { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            runCatching { app.api.cronRuns(jobId).map { it.toCronRun() }.sortedByDescending { it.startedAt } }
                .onSuccess {
                    runs = it
                    failed = null
                }
                .onFailure { failed = userMessage(it) }
            loading = false
            refreshing = false
        }
    }

    LaunchedEffect(jobId) { load() }
    LaunchedEffect(tick) { if (tick > 0) load() }

    ScreenScaffold(title = title, snackbar = snackbar, onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SnackMessage(snackbar, failed)
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                runs == null -> Message("The runs did not load.", failed.orEmpty(), "Retry" to ::load)
                runs!!.isEmpty() -> Message("No runs.", "This job did not run yet.", null)
                else -> PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; load() }) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(runs!!, key = { it.id }) { run ->
                            RunItem(run, title, if (run.reportPath.isEmpty()) onOpenThread else onOpenReport)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/** One run row. A script run has the job as its name and opens its report. */
@Composable
private fun RunItem(run: CronRun, jobTitle: String, onOpen: (CronRun) -> Unit) {
    val script = run.reportPath.isNotEmpty()
    Column(Modifier.fillMaxWidth().clickable { onOpen(run) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (script) jobTitle else run.title.ifBlank { "Run" },
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RunStatus(run)
        }
        Text(formatTime(run.startedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val detail = when {
            script -> "Script report"
            run.messageCount > 0 -> "${run.messageCount} messages"
            else -> ""
        }
        if (detail.isNotEmpty()) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "Running" while the run is active, else "Failed" when the gateway named a failure. */
@Composable
private fun RunStatus(run: CronRun) {
    when {
        run.running -> RunningLabel()
        run.failed -> Text("Failed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
    }
}

private val failureWords = listOf("fail", "error", "timeout")

/** True when the gateway ended the run with a failure. */
private val CronRun.failed: Boolean
    get() = failureWords.any { endReason.contains(it, ignoreCase = true) }

@Composable
private fun RunningLabel() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Text("Running", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}

/** One report of a script run, as plain text. */
@Composable
internal fun ReportScreen(path: String, title: String, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val app = App.instance
    var text by remember(path) { mutableStateOf<String?>(null) }
    var failed by remember(path) { mutableStateOf<String?>(null) }
    var attempt by remember(path) { mutableStateOf(0) }

    LaunchedEffect(path, attempt) {
        runCatching { app.api.readText(path) }
            .onSuccess { text = it }
            .onFailure { failed = userMessage(it) }
    }

    ScreenScaffold(title = title, snackbar = snackbar, onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SnackMessage(snackbar, failed)
            val body = text
            when {
                body != null -> Text(
                    body,
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                )
                failed != null -> Message("The report did not load.", failed.orEmpty(), "Retry" to { attempt++ })
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}
