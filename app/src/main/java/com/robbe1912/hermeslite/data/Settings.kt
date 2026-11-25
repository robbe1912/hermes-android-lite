package com.robbe1912.hermeslite.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.UUID

private val Context.store: DataStore<Preferences> by preferencesDataStore("settings")

enum class AuthMode { Password, Token }

/** How the app reaches the gateway. */
enum class RouteMode {
    /** The LAN at home, the tailnet away. */
    Auto,

    /** The LAN only. */
    Direct,

    /** The tailnet inside the app only. */
    Tailnet,
}

/**
 * The gateway as the user set it up.
 *
 * [address] is host:port. [secret] is the password or the dashboard token.
 */
data class ServerConfig(
    val address: String = "",
    val https: Boolean = false,
    val authMode: AuthMode = AuthMode.Password,
    val username: String = "",
    val secret: String = "",
    val routeMode: RouteMode = RouteMode.Auto,
) {
    val isSetUp: Boolean
        get() = address.contains(':') && secret.isNotBlank() &&
            (authMode == AuthMode.Token || username.isNotBlank())
}

/**
 * The Firebase project of the user. The relay on the gateway server sends the push through it.
 *
 * The values come from the google-services.json of the user. The file stays on the device.
 */
data class PushConfig(
    val projectId: String = "",
    val apiKey: String = "",
    val appId: String = "",
    val senderId: String = "",
) {
    val isSetUp: Boolean
        get() = listOf(projectId, apiKey, appId, senderId).all { it.isNotBlank() }

    companion object {
        fun fromServicesJson(text: String, packageName: String): PushConfig? {
            val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            val info = root.obj("project_info") ?: return null
            val clients = root.arr("client")?.mapNotNull { it as? JsonObject } ?: return null
            val client = clients.firstOrNull { packageNameOf(it) == packageName }
                ?: clients.firstOrNull { packageNameOf(it) != null }
                ?: return null
            val config = PushConfig(
                projectId = info.value("project_id"),
                apiKey = (client.arr("api_key")?.firstOrNull() as? JsonObject)?.value("current_key") ?: "",
                appId = client.obj("client_info")?.value("mobilesdk_app_id") ?: "",
                senderId = info.value("project_number"),
            )
            return config.takeIf { it.isSetUp }
        }

        private fun packageNameOf(client: JsonObject): String? =
            client.obj("client_info")?.obj("android_client_info")?.value("package_name")

        private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

        private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

        /** The text of a field. An empty string for a missing field. */
        private fun JsonObject.value(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""
    }
}

/** Stores the setup of the app. The secret is encrypted with a key in the Android Keystore. */
class Settings(private val context: Context) {
    /** null until the stored values are read. */
    val server = MutableStateFlow<ServerConfig?>(null)

    /** null until the stored values are read. Empty values mean that push is off. */
    val push = MutableStateFlow<PushConfig?>(null)

    /** A random id of this install. It is "" until it is read. The gateway knows the device under it. */
    val installId = MutableStateFlow("")

    /** true when the app reads answers aloud. The setting stays when the user forgets the gateway. */
    val readAloud = MutableStateFlow(false)

    fun loadAsync(scope: CoroutineScope) {
        scope.launch {
            val stored = try {
                context.store.data.first()
            } catch (e: Exception) {
                // A damaged file has no setup. The user enters the gateway again.
                null
            }
            installId.value = stored?.get(INSTALL_ID)?.takeIf { it.isNotBlank() } ?: newInstallId()
            readAloud.value = stored?.get(READ_ALOUD) ?: false
            server.value = ServerConfig(
                address = stored?.get(ADDRESS) ?: "",
                https = stored?.get(HTTPS) ?: false,
                authMode = if (stored?.get(AUTH_MODE) == AuthMode.Token.name) AuthMode.Token else AuthMode.Password,
                username = stored?.get(USERNAME) ?: "",
                secret = stored?.get(SECRET)?.let { SecretBox.decrypt(it) } ?: "",
                routeMode = RouteMode.entries.firstOrNull { it.name == stored?.get(ROUTE_MODE) } ?: RouteMode.Auto,
            )
            push.value = PushConfig(
                projectId = stored?.get(PUSH_PROJECT).orEmpty(),
                apiKey = stored?.get(PUSH_API_KEY).orEmpty(),
                appId = stored?.get(PUSH_APP_ID).orEmpty(),
                senderId = stored?.get(PUSH_SENDER).orEmpty(),
            )
        }
    }

    /** Stores the configuration. [config] becomes the active one. */
    suspend fun save(config: ServerConfig) {
        context.store.edit { stored ->
            stored[ADDRESS] = config.address
            stored[HTTPS] = config.https
            stored[AUTH_MODE] = config.authMode.name
            stored[USERNAME] = config.username
            stored[SECRET] = SecretBox.encrypt(config.secret)
            stored[ROUTE_MODE] = config.routeMode.name
        }
        server.value = config
    }

    /** Stores the Firebase project. Push is off when [config] is not set up. */
    suspend fun savePush(config: PushConfig) {
        context.store.edit { stored ->
            stored[PUSH_PROJECT] = config.projectId
            stored[PUSH_API_KEY] = config.apiKey
            stored[PUSH_APP_ID] = config.appId
            stored[PUSH_SENDER] = config.senderId
        }
        push.value = config
    }

    /** Stores the read aloud setting. */
    suspend fun saveReadAloud(on: Boolean) {
        context.store.edit { stored -> stored[READ_ALOUD] = on }
        readAloud.value = on
    }

    /** Forgets the gateway and the Firebase project. The app shows the setup screen again. */
    suspend fun clear() {
        context.store.edit { stored ->
            stored.clear()
            stored[READ_ALOUD] = readAloud.value
        }
        server.value = ServerConfig()
        push.value = PushConfig()
        installId.value = newInstallId()
    }

    /** Makes a new install id and stores it. */
    private suspend fun newInstallId(): String {
        val id = UUID.randomUUID().toString()
        context.store.edit { stored -> stored[INSTALL_ID] = id }
        return id
    }

    private companion object {
        val ADDRESS = stringPreferencesKey("server_address")
        val HTTPS = booleanPreferencesKey("server_https")
        val AUTH_MODE = stringPreferencesKey("server_auth_mode")
        val USERNAME = stringPreferencesKey("server_username")
        val SECRET = stringPreferencesKey("server_secret_enc")
        val ROUTE_MODE = stringPreferencesKey("server_route_mode")
        val PUSH_PROJECT = stringPreferencesKey("push_project_id")
        val PUSH_API_KEY = stringPreferencesKey("push_api_key")
        val PUSH_APP_ID = stringPreferencesKey("push_app_id")
        val PUSH_SENDER = stringPreferencesKey("push_sender_id")
        val INSTALL_ID = stringPreferencesKey("install_id")
        val READ_ALOUD = booleanPreferencesKey("read_aloud")
    }
}
