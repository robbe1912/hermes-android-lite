package com.robbe1912.hermeslite.push

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.robbe1912.hermeslite.data.PushConfig
import com.robbe1912.hermeslite.data.ServerConfig
import com.robbe1912.hermeslite.data.Settings
import com.robbe1912.hermeslite.gateway.GatewayApi
import com.robbe1912.hermeslite.ui.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Starts Firebase with the project of the user. Then it registers this device at the gateway.
 *
 * The gateway holds one small file for each device below `hermes-lite-push`. The relay on the
 * gateway server reads these files. It sends the wake ping of a cron run to each device.
 */
class PushController(
    private val context: Context,
    private val settings: Settings,
    private val api: GatewayApi,
    private val scope: CoroutineScope,
) {
    /** "Off" without a project. Else the state of the registration, or the error. */
    val state = MutableStateFlow("Off")

    /** Starts when the stored values are read. Each change of the setup runs it again. */
    fun start() {
        scope.launch {
            combine(settings.push, settings.server) { push, server -> push to server }.collectLatest { (push, server) ->
                apply(push, server)
            }
        }
    }

    /** Firebase made a new token for this device. Register it again. */
    fun onNewToken(token: String) {
        scope.launch {
            val push = settings.push.value
            val server = settings.server.value
            if (push == null || server == null || !push.isSetUp || !server.isSetUp) return@launch
            try {
                register(push, token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.value = "Error: ${userMessage(e)}"
            }
        }
    }

    private suspend fun apply(push: PushConfig?, server: ServerConfig?) {
        if (push == null || server == null) return
        if (!push.isSetUp) {
            state.value = "Off"
            return
        }
        if (!server.isSetUp) {
            state.value = "Waiting for the gateway setup."
            return
        }
        try {
            startFirebase(push)
            state.value = "Getting the device token."
            register(push, token())
            state.value = "On. This device is registered at the gateway."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.value = "Error: ${userMessage(e)}"
        }
    }

    private fun startFirebase(push: PushConfig) {
        val options = FirebaseOptions.Builder()
            .setProjectId(push.projectId)
            .setApiKey(push.apiKey)
            .setApplicationId(push.appId)
            .setGcmSenderId(push.senderId)
            .build()
        val app = FirebaseApp.getApps(context).firstOrNull()
        when {
            app == null -> FirebaseApp.initializeApp(context, options)
            app.options.projectId != push.projectId -> {
                app.delete()
                FirebaseApp.initializeApp(context, options)
            }
        }
    }

    /** Waits for the token that Firebase gives to this device. */
    @Suppress("DEPRECATION") // This Firebase version has no other way to read the token.
    private suspend fun token(): String = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
            .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    }

    /** Writes the device file below hermes-lite-push. The file name is the id of this install. */
    private suspend fun register(push: PushConfig, token: String) {
        val body = Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("token", token)
                put("project_id", push.projectId)
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("app_version", versionName())
                put("registered_at", Instant.now().toString())
            },
        )
        api.writeText("hermes-lite-push/${settings.installId.value}.json", body)
    }

    private fun versionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")
}
