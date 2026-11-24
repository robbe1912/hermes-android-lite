package com.robbe1912.hermeslite

import android.app.Application
import com.robbe1912.hermeslite.data.Settings
import com.robbe1912.hermeslite.gateway.GatewayApi
import com.robbe1912.hermeslite.gateway.GatewaySocket
import com.robbe1912.hermeslite.tunnel.TunnelController
import com.robbe1912.hermeslite.ui.Threads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Holds the singletons of the app. They live as long as the process. */
class App : Application() {
    /** The scope for work that must survive screen changes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings by lazy { Settings(this) }
    val tunnel by lazy { TunnelController(this) }
    val api by lazy { GatewayApi(settings, tunnel) }
    val socket by lazy { GatewaySocket(api) }
    val threads by lazy { Threads(api, socket, appScope) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings.loadAsync(appScope)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
