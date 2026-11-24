package com.robbe1912.hermeslite.tunnel

import android.content.Context
import android.os.SystemClock
import android.provider.Settings as SystemSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

sealed interface TunnelState {
    data object Stopped : TunnelState
    data object Starting : TunnelState
    data object NeedsLogin : TunnelState
    /** The user must open [url] and approve the device. */
    data class NeedsApproval(val url: String) : TunnelState
    data class Running(val addresses: List<String>) : TunnelState
    data class Failed(val message: String) : TunnelState
}

class TunnelException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The Tailscale node of the app, over the Go library in `tunnel`.
 *
 * The node starts once and stays on while the app runs. Its state is on disk, so the next start
 * needs no new approval.
 */
class TunnelController(context: Context) {
    private val stateDir = File(context.filesDir, "tunnel")
    private val hostname: String
    private val lock = Mutex()
    private val _state = MutableStateFlow<TunnelState>(TunnelState.Stopped)
    val state: StateFlow<TunnelState> = _state.asStateFlow()
    private var port = 0
    private var target: String? = null
    /** Changes on stop and logout. A waiting sign-in ends when it changes. */
    private var generation = 0

    init {
        val suffix = SystemSettings.Secure.getString(context.contentResolver, SystemSettings.Secure.ANDROID_ID)
            ?.takeLast(4)?.lowercase() ?: "0000"
        hostname = "hermes-lite-$suffix"
    }

    /**
     * Starts the node and waits until it runs. The state shows the approval link while the user
     * approves the device. Throws [TunnelException] when the node fails or the time is over.
     */
    suspend fun ensureRunning(timeoutMs: Long = APPROVAL_WAIT_MS) {
        val started = lock.withLock {
            if (_state.value is TunnelState.Running) return
            _state.value = TunnelState.Starting
            try {
                call { tunnel.Tunnel.start(stateDir.absolutePath, hostname, "", "", false) }
            } catch (e: Exception) {
                // The node can already run in this process. The status read below tells.
            }
            generation
        }
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var loginAsked = false
        // The wait runs without the lock, so stop and logout can end it.
        while (true) {
            val current = readState(call { tunnel.Tunnel.status() })
            when (current) {
                is TunnelState.Running -> {
                    if (generation == started) _state.value = current
                    return
                }
                TunnelState.NeedsLogin -> {
                    if (generation == started) _state.value = current
                    if (!loginAsked) {
                        call { tunnel.Tunnel.login() }
                        loginAsked = true
                    }
                }
                is TunnelState.Failed -> {
                    if (generation == started) _state.value = current
                    throw TunnelException(current.message)
                }
                TunnelState.Stopped -> throw TunnelException("The tailnet node did not start.")
                TunnelState.Starting, is TunnelState.NeedsApproval ->
                    if (generation == started) _state.value = current
            }
            if (generation != started) throw TunnelException("The tailnet node was stopped.")
            if (SystemClock.elapsedRealtime() > deadline) {
                throw TunnelException("Timeout. The device was not approved.")
            }
            delay(POLL_MS)
        }
    }

    /**
     * Opens a local port for [target] (host:port) and returns the port. Calls for the same target
     * share one port. Other apps on the device can reach the port. So open it only for the gateway.
     */
    suspend fun forward(target: String): Int {
        if (port != 0 && this.target == target) return port
        ensureRunning()
        return lock.withLock {
            if (port != 0 && this.target == target) return@withLock port
            val opened = call { tunnel.Tunnel.forward(0, target) }.toInt()
            port = opened
            this.target = target
            opened
        }
    }

    /** Stops the node. The login on disk stays. */
    suspend fun stop() {
        lock.withLock {
            try {
                call { tunnel.Tunnel.stop() }
            } catch (e: Exception) {
                // The node was not started.
            }
            reset()
        }
    }

    /** Removes the device from the tailnet. The next start needs a new approval. */
    suspend fun logout() {
        lock.withLock {
            try {
                call { tunnel.Tunnel.logout() }
            } catch (e: Exception) {
                // The node was not started. Nothing is on disk for a running node only.
            }
            reset()
        }
    }

    /** The last lines of the node log, for problem reports. */
    suspend fun logs(): String = call { tunnel.Tunnel.logs(40) }

    private fun reset() {
        port = 0
        target = null
        generation++
        _state.value = TunnelState.Stopped
    }

    private fun readState(json: String): TunnelState {
        val root = Json.parseToJsonElement(json).jsonObject
        val addresses = root["ips"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        return when (root["state"]?.jsonPrimitive?.content) {
            "running" -> TunnelState.Running(addresses)
            "needsApproval" -> TunnelState.NeedsApproval(root["loginUrl"]?.jsonPrimitive?.content ?: "")
            "needsLogin" -> TunnelState.NeedsLogin
            "starting" -> TunnelState.Starting
            "error" -> TunnelState.Failed(root["error"]?.jsonPrimitive?.content ?: "Unknown error.")
            else -> TunnelState.Stopped
        }
    }

    /** The Go calls block. They run on the IO threads. */
    private suspend fun <T> call(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        const val POLL_MS = 1_000L

        /** The time for the user to approve the device. */
        const val APPROVAL_WAIT_MS = 10 * 60_000L
    }
}
