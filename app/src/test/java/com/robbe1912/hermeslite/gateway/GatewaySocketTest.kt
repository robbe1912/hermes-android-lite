package com.robbe1912.hermeslite.gateway

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class GatewaySocketTest {
    @Test
    fun advertisesServerRequestsAfterGatewayReady() = runBlocking {
        val received = CopyOnWriteArrayList<JsonObject>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    Json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("method", "event")
                            put("params", buildJsonObject { put("type", "gateway.ready") })
                        },
                    ),
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val request = Json.parseToJsonElement(text).jsonObject
                received += request
                val id = request["id"] as? JsonPrimitive ?: return
                webSocket.send(
                    Json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("id", id)
                            put("result", JsonObject(emptyMap()))
                        },
                    ),
                )
            }
        }
        val server = MockWebServer()
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(listener)
                .addHeader("Sec-WebSocket-Protocol", "hermes-gateway-v1")
                .build(),
        )
        server.start()
        val client = OkHttpClient()
        val socket = GatewaySocket(client) { server.url("/api/ws?ticket=one-use") }

        try {
            socket.ensureConnected()

            assertTrue(socket.connected.value)
            assertEquals(1, received.size)
            assertEquals("client.capabilities", received.single()["method"]?.jsonPrimitive?.content)
            assertEquals(
                "true",
                received.single()["params"]?.jsonObject?.get("server_requests")?.jsonPrimitive?.content,
            )
            assertEquals("hermes-gateway-v1", server.takeRequest().headers["Sec-WebSocket-Protocol"])
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            server.close()
        }
    }
}
