package com.robbe1912.hermeslite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robbe1912.hermeslite.App
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The screens of the app. A thread carries the id of its stored session. */
sealed interface Screen {
    data object Channels : Screen
    data object Cron : Screen
    data object Settings : Screen
    data class Thread(val storedId: String?, val title: String) : Screen
    data class CronRuns(val jobId: String, val title: String) : Screen
    /** A report of a script run. The path is relative to the gateway file root. */
    data class Report(val path: String, val title: String) : Screen
}

/** A plain back stack of screens. The first item stays at the bottom. */
class Navigator(root: Screen) {
    private val backing = MutableStateFlow(listOf(root))

    /** The stack from the bottom to the top. */
    val stack: StateFlow<List<Screen>> = backing
    val canGoBack: Boolean get() = backing.value.size > 1

    fun push(screen: Screen) {
        backing.value = backing.value + screen
    }

    fun pop(): Boolean {
        if (!canGoBack) return false
        backing.value = backing.value.dropLast(1)
        return true
    }

    /** Replaces the stack. The first item is the bottom. */
    fun replaceAll(vararg screens: Screen) {
        backing.value = screens.toList()
    }
}

/** The root of the app: it owns the back stack and the singletons. */
@Composable
fun AppRoot() {
    val app = App.instance
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val navigator = remember { Navigator(Screen.Channels) }

    // Counts the refreshes. A wake-up from a notification refreshes the lists.
    val refresh = remember { MutableStateFlow(0) }
    val refreshTick by refresh.collectAsStateWithLifecycle()
    val target by app.wake.collectAsStateWithLifecycle()
    val config by app.settings.server.collectAsStateWithLifecycle()
    val setOn = config?.isSetUp == true
    val stack by navigator.stack.collectAsStateWithLifecycle()

    fun showError(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    BackHandler(enabled = navigator.canGoBack) { navigator.pop() }

    // The app starts at the channel list when the setup is new or changed.
    var wasOn by remember { mutableStateOf(setOn) }
    LaunchedEffect(setOn) {
        if (setOn != wasOn) {
            wasOn = setOn
            navigator.replaceAll(Screen.Channels)
        }
    }

    if (!setOn) {
        ScreenScaffold(title = "Welcome", snackbar = snackbar) { padding ->
            SetupScreen(Modifier.padding(padding), onSaved = { navigator.replaceAll(Screen.Channels) }, onError = ::showError)
        }
        return
    }

    // A notification opens a run. The app takes the target once, and then clears it.
    LaunchedEffect(target) {
        val run = target ?: return@LaunchedEffect
        if (run.sessionId != null) {
            navigator.replaceAll(Screen.Channels, Screen.Thread(run.sessionId, run.title))
        } else {
            navigator.replaceAll(Screen.Cron, Screen.CronRuns(run.jobId, run.title))
        }
        refresh.value++
        app.wake.value = null
    }

    // A tap on a tab of the bottom bar makes that root the only screen on the stack.
    fun openTab(tab: RootTab) {
        navigator.replaceAll(if (tab == RootTab.Channels) Screen.Channels else Screen.Cron)
    }

    when (val screen = stack.last()) {
        Screen.Channels -> ChannelsScreen(
            snackbar = snackbar,
            tick = refreshTick,
            onOpen = { navigator.push(Screen.Thread(it.id, it.title)) },
            onNew = { navigator.push(Screen.Thread(null, "")) },
            onSettings = { navigator.push(Screen.Settings) },
            onTab = ::openTab,
        )
        Screen.Cron -> CronScreen(
            snackbar = snackbar,
            tick = refreshTick,
            onOpen = { navigator.push(Screen.CronRuns(it.id, it.name)) },
            onSettings = { navigator.push(Screen.Settings) },
            onTab = ::openTab,
        )
        is Screen.CronRuns -> CronRunsScreen(
            jobId = screen.jobId,
            title = screen.title,
            snackbar = snackbar,
            tick = refreshTick,
            onBack = { navigator.pop() },
            onOpenThread = { navigator.push(Screen.Thread(it.id, it.title)) },
            onOpenReport = { navigator.push(Screen.Report(it.reportPath, screen.title)) },
        )
        is Screen.Report -> ReportScreen(
            path = screen.path,
            title = screen.title,
            snackbar = snackbar,
            onBack = { navigator.pop() },
        )
        Screen.Settings -> SettingsScreen(snackbar = snackbar, onDone = { navigator.pop() }, onError = ::showError)
        is Screen.Thread -> {
            val model = remember(screen.storedId) { app.threads.get(screen.storedId) }
            ThreadScreen(
                model = model,
                title = screen.title,
                snackbar = snackbar,
                onBack = { navigator.pop() },
                onVoiceSettings = { navigator.push(Screen.Settings) },
                onCreated = { id ->
                    app.threads.adopt(id)
                    navigator.replaceAll(Screen.Channels, Screen.Thread(id, ""))
                },
            )
        }
    }
}
