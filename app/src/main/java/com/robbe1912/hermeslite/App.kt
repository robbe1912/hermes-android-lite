package com.robbe1912.hermeslite

import android.app.Application
import android.content.Intent
import com.robbe1912.hermeslite.data.Settings
import com.robbe1912.hermeslite.gateway.GatewayApi
import com.robbe1912.hermeslite.gateway.GatewaySocket
import com.robbe1912.hermeslite.push.PushController
import com.robbe1912.hermeslite.push.WakeIntent
import com.robbe1912.hermeslite.tunnel.TunnelController
import com.robbe1912.hermeslite.ui.Threads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/** Holds the singletons of the app. They live as long as the process. */
class App : Application() {
    /** The scope for work that must survive screen changes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings by lazy { Settings(this) }
    val tunnel by lazy { TunnelController(this) }
    val api by lazy { GatewayApi(settings, tunnel) }
    val socket by lazy { GatewaySocket(api) }
    val threads by lazy { Threads(api, socket, appScope) }
    val push by lazy { PushController(this, settings, api, appScope) }

    /** The run that a notification opens. The root screen takes it and sets it back to null. */
    val wake = MutableStateFlow<WakeIntent.Target?>(null)

    /** The last intent that set [wake]. A recreated activity gets the same intent again. */
    private val lastWakeIntent = AtomicReference<Intent?>(null)

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings.loadAsync(appScope)
        push.start()
    }

    /**
     * Reads the run from an intent of a notification. A foreign intent changes nothing.
     * The stored values can be unread at a cold start, so the read waits for them.
     */
    fun openWake(intent: Intent?) {
        if (intent == null || intent === lastWakeIntent.getAndSet(intent)) return
        appScope.launch {
            settings.installId.first { it.isNotBlank() }
            wake.value = WakeIntent.target(intent, settings.installId.value)
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
