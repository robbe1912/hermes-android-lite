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
import com.robbe1912.hermeslite.voice.ReadAloud
import com.robbe1912.hermeslite.voice.VoiceInput
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
    val threads by lazy { Threads(api, socket, appScope, ::onAnswer) }
    val push by lazy { PushController(this, settings, api, appScope) }
    val voice by lazy { VoiceInput(this) }
    val readAloud by lazy { ReadAloud(this) }

    /** The run that a notification opens. The root screen takes it and sets it back to null. */
    val wake = MutableStateFlow<WakeIntent.Target?>(null)

    /** The last intent that set [wake]. A recreated activity gets the same intent again. */
    private val lastWakeIntent = AtomicReference<Intent?>(null)

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings.loadAsync(appScope)
        push.start()
        // Voice settings loads the installed voices. The output switch also starts the engine.
        appScope.launch { settings.readAloud.collect { if (it) readAloud.prepare() } }
        appScope.launch { settings.ttsVoice.collect { readAloud.selectVoice(it) } }
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

    /** Reads a finished answer aloud when the user turned the setting on. */
    private fun onAnswer(text: String) {
        if (settings.readAloud.value) readAloud.speak(text)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
