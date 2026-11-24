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

/** Stores the gateway setup. The secret is encrypted with a key in the Android Keystore. */
class Settings(private val context: Context) {
    /** null until the stored values are read. */
    val server = MutableStateFlow<ServerConfig?>(null)

    fun loadAsync(scope: CoroutineScope) {
        scope.launch {
            server.value = read()
        }
    }

    private suspend fun read(): ServerConfig {
        val stored = try {
            context.store.data.first()
        } catch (e: Exception) {
            // A damaged file has no setup. The user enters the gateway again.
            null
        }
        return ServerConfig(
            address = stored?.get(ADDRESS) ?: "",
            https = stored?.get(HTTPS) ?: false,
            authMode = if (stored?.get(AUTH_MODE) == AuthMode.Token.name) AuthMode.Token else AuthMode.Password,
            username = stored?.get(USERNAME) ?: "",
            secret = stored?.get(SECRET)?.let { SecretBox.decrypt(it) } ?: "",
            routeMode = RouteMode.entries.firstOrNull { it.name == stored?.get(ROUTE_MODE) } ?: RouteMode.Auto,
        )
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

    /** Forgets the gateway. The app shows the setup screen again. */
    suspend fun clear() {
        context.store.edit { stored -> stored.clear() }
        server.value = ServerConfig()
    }

    private companion object {
        val ADDRESS = stringPreferencesKey("server_address")
        val HTTPS = booleanPreferencesKey("server_https")
        val AUTH_MODE = stringPreferencesKey("server_auth_mode")
        val USERNAME = stringPreferencesKey("server_username")
        val SECRET = stringPreferencesKey("server_secret_enc")
        val ROUTE_MODE = stringPreferencesKey("server_route_mode")
    }
}
