package com.robbe1912.hermeslite.gateway

import android.os.SystemClock
import com.robbe1912.hermeslite.data.AuthMode
import com.robbe1912.hermeslite.data.RouteMode
import com.robbe1912.hermeslite.data.ServerConfig
import com.robbe1912.hermeslite.data.Settings
import com.robbe1912.hermeslite.tunnel.TunnelController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

open class GatewayException(message: String, cause: Throwable? = null) : Exception(message, cause)
class GatewayRpcError(val code: Int?, message: String) : GatewayException(message)
class NotSetUpException : GatewayException("Set up the gateway first.")

/** One login per gateway. The cookies are used for the LAN address and for the local forward port. */
internal class GatewayCookies : CookieJar {
    @Volatile
    private var stored: List<Cookie> = emptyList()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = synchronized(this) {
        val now = System.currentTimeMillis()
        stored = stored.filter { old -> cookies.none { it.name == old.name } } + cookies.filter { it.expiresAt > now }
        Unit
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = stored

    fun clear() {
        stored = emptyList()
    }
}

/** Splits "host:port". A missing port gives the default port of the scheme. */
internal fun splitAddress(address: String, https: Boolean): Pair<String, Int> {
    val default = if (https) 443 else 80
    val text = address.trim()
    if (text.startsWith("[")) {
        val end = text.indexOf(']')
        if (end > 0) {
            return text.substring(1, end) to (text.substring(end + 1).removePrefix(":").toIntOrNull() ?: default)
        }
    }
    val cut = text.lastIndexOf(':')
    if (cut > 0 && text.indexOf(':') == cut) {
        return text.substring(0, cut) to (text.substring(cut + 1).toIntOrNull() ?: default)
    }
    return text to default
}

/**
 * The REST API of the gateway.
 *
 * Each request picks its route first: the LAN, or the tailnet forward of [TunnelController].
 * With the password login, a rejected call logs in again and runs once more.
 */
class GatewayApi(internal val settings: Settings, internal val tunnel: TunnelController) {
    /** True when the last request went through the tailnet forward. */
    val onTailnet = MutableStateFlow(false)

    internal val cookies = GatewayCookies()

    internal val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookies)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(LAN_PROBE_MS, TimeUnit.MILLISECONDS)
        .readTimeout(LAN_PROBE_MS, TimeUnit.MILLISECONDS)
        .build()

    /** When the LAN route failed last, in elapsed ms. 0 is never. */
    @Volatile
    private var lanFailedAt = 0L

    /** A configuration that is not saved yet. probe() uses it for its calls. */
    @Volatile
    private var trial: ServerConfig? = null

    /** Forgets the login and the route state. Call it after a setup change. */
    fun reset() {
        cookies.clear()
        lanFailedAt = 0
    }

    /** The base URL for the next request. It picks the LAN or the tailnet route. */
    suspend fun baseUrl(): HttpUrl {
        val config = activeConfig()
        val lan = try {
            lanUrl(config)
        } catch (e: IllegalArgumentException) {
            throw GatewayException("The gateway address is not valid. Use host:port.", e)
        }
        when (config.routeMode) {
            RouteMode.Direct -> return useLan(lan)
            RouteMode.Tailnet -> return tailnetUrl(config)
            RouteMode.Auto -> {
                val now = SystemClock.elapsedRealtime()
                if (lanFailedAt == 0L || now - lanFailedAt > LAN_RETRY_MS) {
                    if (lanReachable(lan)) return useLan(lan)
                    lanFailedAt = now
                }
                return tailnetUrl(config)
            }
        }
    }

    /** The status of the gateway, with the version and the gateway state. */
    suspend fun status(): JsonObject = request("GET", "/api/status").jsonObject

    /** Calls the gateway with [config], which can be unsaved. The setup form uses it. */
    suspend fun probe(config: ServerConfig): JsonObject = try {
        trial = config
        reset()
        status()
    } finally {
        trial = null
    }

    /** The stored channels, newest first. Cron runs are not in this list. They have their own screen. */
    suspend fun channels(): List<ChannelRow> =
        request("GET", "/api/sessions", mapOf("limit" to "100", "offset" to "0", "order" to "recent"))
            .jsonObject.arr("sessions")
            ?.map { it.jsonObject.toChannelRow() }
            ?.filter { it.source != "cron" }
            ?: emptyList()

    /** The latest [limit] stored messages of a thread, in chronological order. */
    suspend fun messages(storedId: String, limit: Int = 200): List<StoredMessage> =
        request("GET", "api/sessions/$storedId/messages", mapOf("limit" to limit.toString(), "offset" to "0", "order" to "latest"))
            .jsonObject.arr("messages")
            ?.map { it.jsonObject.toStoredMessage() }
            ?: emptyList()

    /** The cron jobs of all profiles. */
    suspend fun cronJobs(): List<JsonObject> =
        request("GET", "/api/cron/jobs", mapOf("profile" to "all")).jsonArray.map { it.jsonObject }

    /** The runs of one cron job, newest first. A run is a thread. */
    suspend fun cronRuns(jobId: String, limit: Int = 20): List<JsonObject> =
        request("GET", "api/cron/jobs/$jobId/runs", mapOf("limit" to limit.toString()))
            .jsonObject.arr("runs")
            ?.map { it.jsonObject }
            ?: emptyList()

    /** One text file below the gateway file root. [path] is relative to the root. */
    suspend fun readText(path: String): String {
        val dataUrl = request("GET", "/api/files/read", mapOf("path" to path)).jsonObject.str("data_url")
            ?: throw GatewayException("The gateway sent no file data.")
        val comma = dataUrl.indexOf(',')
        if (!dataUrl.startsWith("data:") || comma < 0 || !dataUrl.substring(0, comma).contains("base64")) {
            throw GatewayException("The gateway sent a file in an unknown format.")
        }
        return String(Base64.getDecoder().decode(dataUrl.substring(comma + 1)), Charsets.UTF_8)
    }

    /** Writes one small text file below the gateway file root. [path] is relative to the root. */
    suspend fun writeText(path: String, text: String, mime: String = "application/json") {
        val dataUrl = "data:$mime;base64," + Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        request(
            "POST", "/api/files/upload",
            body = buildJsonObject {
                put("path", path)
                put("data_url", dataUrl)
                put("overwrite", true)
            },
        )
    }

    /** Deletes one file below the gateway file root. [path] is relative to the root. */
    suspend fun deleteFile(path: String) {
        request("DELETE", "/api/files", body = buildJsonObject { put("path", path) })
    }

    /** The URL of the gateway socket. The ticket in the URL is valid for 30 seconds and one use. */
    suspend fun webSocketUrl(): HttpUrl {
        val base = baseUrl()
        val ticket = request("POST", "/api/auth/ws-ticket").jsonObject.str("ticket")
            ?: throw GatewayException("The gateway gave no socket ticket.")
        // OkHttp needs the http or https URL of the socket. It does the WebSocket upgrade itself.
        return base.newBuilder()
            .addPathSegments("api/ws")
            .addQueryParameter("ticket", ticket)
            .build()
    }

    private fun activeConfig(): ServerConfig =
        trial ?: settings.server.value?.takeIf { it.isSetUp } ?: throw NotSetUpException()

    private fun lanUrl(config: ServerConfig): HttpUrl {
        val (host, port) = splitAddress(config.address, config.https)
        return HttpUrl.Builder().scheme(if (config.https) "https" else "http").host(host).port(port).build()
    }

    private fun useLan(lan: HttpUrl): HttpUrl {
        onTailnet.value = false
        return lan
    }

    private suspend fun tailnetUrl(config: ServerConfig): HttpUrl {
        val port = tunnel.forward(config.address)
        onTailnet.value = true
        return HttpUrl.Builder().scheme(if (config.https) "https" else "http").host(LOOPBACK).port(port).build()
    }

    private suspend fun lanReachable(lan: HttpUrl): Boolean = withContext(Dispatchers.IO) {
        try {
            probeClient.newCall(Request.Builder().url(url(lan, "/api/health")).build()).execute().use { true }
        } catch (e: IOException) {
            false
        }
    }

    private class Reply(val code: Int, val text: String)

    private suspend fun request(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JsonObject? = null,
    ): JsonElement {
        val config = activeConfig()
        var base = baseUrl()
        var loggedIn = false
        var rerouted = false
        while (true) {
            try {
                val reply = send(method, url(base, path, query), config, body)
                if (reply.code < 400) return parse(reply.text)
                when {
                    reply.code == 401 && config.authMode == AuthMode.Password && !loggedIn -> {
                        cookies.clear()
                        login(config, base)
                        loggedIn = true
                    }
                    reply.code == 401 && config.authMode == AuthMode.Token ->
                        throw GatewayException("The gateway rejected the token.")
                    reply.code == 401 ->
                        throw GatewayException("The gateway rejected the login. Check the user name and the password.")
                    else -> throw GatewayException(errorText(reply.code, reply.text))
                }
            } catch (e: IOException) {
                val wasLan = base.host != LOOPBACK
                if (wasLan && !rerouted && config.routeMode == RouteMode.Auto) {
                    // The LAN route broke. Continue through the tailnet.
                    rerouted = true
                    lanFailedAt = SystemClock.elapsedRealtime()
                    base = tailnetUrl(config)
                } else {
                    val where = if (wasLan) "on the LAN" else "through the tailnet"
                    throw GatewayException("The gateway is not reachable $where.", e)
                }
            }
        }
    }

    private suspend fun login(config: ServerConfig, base: HttpUrl) {
        val body = buildJsonObject {
            put("provider", "basic")
            put("username", config.username)
            put("password", config.secret)
            put("next", "")
        }
        val reply = send("POST", url(base, "/auth/password-login"), config, body)
        if (reply.code != 200) {
            throw GatewayException(
                if (reply.code == 401) "The gateway rejected the login. Check the user name and the password."
                else errorText(reply.code, reply.text),
            )
        }
    }

    /** One HTTP call. A non-2xx status is a reply, not an exception. */
    private suspend fun send(method: String, target: HttpUrl, config: ServerConfig, body: JsonObject?): Reply =
        withContext(Dispatchers.IO) {
            // OkHttp requires a body for POST. A call without JSON sends an empty body.
            val requestBody = when {
                body != null -> Json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON)
                method == "GET" -> null
                else -> ByteArray(0).toRequestBody(null)
            }
            val builder = Request.Builder().url(target).method(method, requestBody)
            if (config.authMode == AuthMode.Token) {
                builder.header("Authorization", "Bearer ${config.secret}")
            }
            client.newCall(builder.build()).execute().use { response ->
                Reply(response.code, response.body.string())
            }
        }

    private fun url(base: HttpUrl, path: String, query: Map<String, String> = emptyMap()): HttpUrl =
        base.newBuilder().addPathSegments(path.trimStart('/')).apply {
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()

    private fun parse(text: String): JsonElement {
        if (text.isBlank()) return JsonObject(emptyMap())
        return try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw GatewayException("The gateway sent an answer that is not JSON: ${text.take(80)}", e)
        }
    }

    private fun errorText(code: Int, text: String): String {
        val detail = runCatching { Json.parseToJsonElement(text).jsonObject["detail"] }.getOrNull()
        val message = when (detail) {
            is JsonPrimitive -> detail.content
            is JsonObject, is JsonArray -> detail.toString()
            else -> text
        }
        return "The gateway answered HTTP $code: ${message.take(240)}"
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val LAN_PROBE_MS = 2_500L

        /** After this time, the Auto route tests the LAN again. */
        const val LAN_RETRY_MS = 10 * 60_000L
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
