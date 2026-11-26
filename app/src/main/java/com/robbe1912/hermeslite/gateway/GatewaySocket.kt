package com.robbe1912.hermeslite.gateway

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A request from the gateway to the app, for example an approval. The app must answer it. */
class ServerRequest(val id: JsonPrimitive, val method: String, val params: JsonObject)

/** One event frame of the gateway. Events with an empty [sessionId] are global notices. */
class GatewayEvent(val type: String, val sessionId: String, val seq: Long?, val payload: JsonObject)

/**
 * The JSON-RPC socket of the gateway (`/api/ws`).
 *
 * The socket opens with [ensureConnected]. When it drops, the next call opens it again.
 * Callers that need a stream read [events] and [requests].
 */
class GatewaySocket internal constructor(
    private val client: OkHttpClient,
    private val webSocketUrl: suspend () -> HttpUrl,
) {
    constructor(api: GatewayApi) : this(api.client, api::webSocketUrl)
    private val eventFlow = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 4096)
    val events: SharedFlow<GatewayEvent> = eventFlow.asSharedFlow()

    private val requestFlow = MutableSharedFlow<ServerRequest>(extraBufferCapacity = 128)
    val requests: SharedFlow<ServerRequest> = requestFlow.asSharedFlow()

    /** True from `gateway.ready` until the socket drops. */
    val connected = MutableStateFlow(false)

    private var socket: WebSocket? = null
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val nextId = AtomicLong()
    private val connectLock = Mutex()

    /** Opens the socket and negotiates its capabilities after `gateway.ready`. */
    suspend fun ensureConnected() {
        if (connected.value) return
        connectLock.withLock {
            if (connected.value) return
            val ready = CompletableDeferred<Unit>()
            val opened = client.newWebSocket(
                Request.Builder()
                    .url(webSocketUrl())
                    .header("Sec-WebSocket-Protocol", SOCKET_PROTOCOL)
                    .build(),
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) = handle(text, ready)

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (!ready.isCompleted) ready.completeExceptionally(t)
                        lost(webSocket)
                    }
                },
            )
            socket = opened
            try {
                withTimeout(READY_TIMEOUT_MS) { ready.await() }
                callConnected("client.capabilities", buildJsonObject { put("server_requests", true) })
                connected.value = true
            } catch (e: Exception) {
                opened.cancel()
                lost(opened)
                throw GatewayException("The gateway socket did not become ready.", e)
            }
        }
    }

    /** Calls one gateway method and returns its result. Throws [GatewayRpcError] on a gateway error. */
    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
        ensureConnected()
        return callConnected(method, params)
    }

    private suspend fun callConnected(method: String, params: JsonObject): JsonObject {
        val id = nextId.incrementAndGet()
        val answer = CompletableDeferred<JsonObject>()
        pending[id] = answer
        val frame = Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            },
        )
        val current = socket ?: throw GatewayException("The gateway socket is closed.")
        if (!current.send(frame)) {
            pending.remove(id)
            throw GatewayException("The gateway socket is closed.")
        }
        return try {
            withTimeout(CALL_TIMEOUT_MS) { answer.await() }
        } finally {
            pending.remove(id)
        }
    }

    /** Answers one gateway request with a result object. */
    fun respond(id: JsonPrimitive, result: JsonObject) {
        socket?.send(frame(id, "result", result))
    }

    /** Tells the gateway that this app cannot answer the request. The agent continues without an answer. */
    fun reject(request: ServerRequest) {
        socket?.send(
            frame(
                request.id,
                "error",
                buildJsonObject {
                    put("code", -32601)
                    put("message", "This app cannot answer this request.")
                },
            ),
        )
    }

    private fun frame(id: JsonPrimitive, field: String, value: JsonObject): String =
        Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put(field, value)
            },
        )

    private fun handle(text: String, ready: CompletableDeferred<Unit>) {
        val frame = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val id = frame["id"] as? JsonPrimitive
        val method = frame.str("method")
        when {
            id != null && method == null -> answer(id, frame)
            method == "event" -> {
                val params = frame.obj("params") ?: return
                val type = params.str("type") ?: return
                if (type == "gateway.ready") ready.complete(Unit)
                val seq = (params["seq"] as? JsonPrimitive)?.longOrNull
                eventFlow.tryEmit(
                    GatewayEvent(type, params.str("session_id").orEmpty(), seq, params.obj("payload") ?: JsonObject(emptyMap())),
                )
            }
            id != null -> requestFlow.tryEmit(ServerRequest(id, method.orEmpty(), frame.obj("params") ?: JsonObject(emptyMap())))
        }
    }

    private fun answer(id: JsonPrimitive, frame: JsonObject) {
        val key = id.longOrNull ?: return
        val waiter = pending.remove(key) ?: return
        val error = frame.obj("error")
        if (error != null) {
            val code = (error["code"] as? JsonPrimitive)?.longOrNull?.toInt()
            waiter.completeExceptionally(GatewayRpcError(code, error.str("message") ?: "Gateway error."))
        } else {
            waiter.complete(frame.obj("result") ?: JsonObject(emptyMap()))
        }
    }

    /** The socket dropped. Open calls fail, and the next call opens the socket again. */
    private fun lost(which: WebSocket) {
        if (socket !== which) return
        socket = null
        connected.value = false
        val dropped = GatewayException("The gateway socket closed.")
        pending.keys.toList().forEach { key -> pending.remove(key)?.completeExceptionally(dropped) }
    }

    private companion object {
        const val SOCKET_PROTOCOL = "hermes-gateway-v1"
        const val READY_TIMEOUT_MS = 20_000L
        const val CALL_TIMEOUT_MS = 60_000L
    }
}
