package com.robbe1912.hermeslite.ui

import com.robbe1912.hermeslite.gateway.GatewayEvent
import com.robbe1912.hermeslite.gateway.ServerRequest
import com.robbe1912.hermeslite.gateway.StoredMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadModelTest {
    @Test
    fun switchingModelKeepsTheSameThreadSession() = runBlocking {
        val gateway = FakeThreadBackend()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val thread = ThreadModel("stored-1", gateway, scope) {}
            thread.open()
            await { gateway.calls.any { it.first == "model.options" } && !thread.modelsLoading.value }
            assertEquals("current-model", thread.selectedModel.value?.model)
            assertTrue(thread.availableModels.value.any { it.model == "configured-nous-model" })
            assertFalse(thread.availableModels.value.any { it.model == "current-model" })

            val choice = thread.availableModels.value.single { it.model == "glm-5.3" }
            thread.selectModel(choice)
            thread.input.value = "Continue with the same context"
            thread.send()
            await { gateway.calls.any { it.first == "prompt.submit" } }

            val switch = gateway.calls.indexOfFirst { it.first == "command.dispatch" }
            val submit = gateway.calls.indexOfFirst { it.first == "prompt.submit" }
            assertTrue(switch >= 0 && switch < submit)
            assertEquals("/model zai:glm-5.3", gateway.calls[switch].second.str("command"))
            assertEquals("runtime-1", gateway.calls[switch].second.str("session_id"))
            assertEquals("runtime-1", gateway.calls[submit].second.str("session_id"))
            assertFalse(gateway.calls.any { it.first == "session.create" })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun selectedModelIsUsedForTheFirstMessageOfANewThread() = runBlocking {
        val gateway = FakeThreadBackend()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val thread = ThreadModel(null, gateway, scope) {}
            thread.open()
            await { gateway.calls.any { it.first == "model.options" } && !thread.modelsLoading.value }

            thread.selectModel(thread.availableModels.value.single { it.model == "glm-5.3" })
            thread.input.value = "Start with this model"
            thread.send()
            await { gateway.calls.any { it.first == "prompt.submit" } }

            val create = gateway.calls.single { it.first == "session.create" }.second
            val submit = gateway.calls.single { it.first == "prompt.submit" }.second
            assertEquals("zai", create.str("provider"))
            assertEquals("glm-5.3", create.str("model"))
            assertEquals("runtime-new", submit.str("session_id"))
            assertFalse(gateway.calls.any { it.first == "command.dispatch" })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancelThenChangeModelAllowsSendingAgain() = runBlocking {
        val gateway = FakeThreadBackend()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val thread = ThreadModel("stored-1", gateway, scope) {}
            thread.open()
            await { gateway.calls.any { it.first == "model.options" } && !thread.modelsLoading.value }

            thread.input.value = "First message"
            thread.send()
            await { gateway.calls.count { it.first == "prompt.submit" } == 1 }

            thread.stop()
            await { gateway.calls.any { it.first == "session.interrupt" } && !thread.state.value.streaming }

            thread.selectModel(thread.availableModels.value.single { it.model == "glm-5.3" })
            thread.input.value = "Second message"
            thread.send()

            assertEquals(2, gateway.calls.count { it.first == "prompt.submit" })
            assertEquals(null, thread.state.value.error)
            assertEquals("", thread.input.value)
        } finally {
            scope.cancel()
        }
    }


    private suspend fun await(condition: () -> Boolean) = withTimeout(2_000) {
        while (!condition()) yield()
    }
}

private class FakeThreadBackend : ThreadBackend {
    override val connected = MutableStateFlow(false)
    override val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 8)
    override val requests = MutableSharedFlow<ServerRequest>(extraBufferCapacity = 8)
    val calls = mutableListOf<Pair<String, JsonObject>>()

    override suspend fun ensureConnected() {
        connected.value = true
    }

    override suspend fun call(method: String, params: JsonObject): JsonObject {
        calls += method to params
        if (method == "command.dispatch" && params.str("command") == null) {
            throw IllegalStateException("not a quick/plugin/bundle/skill command: ${params.str("name")}")
        }
        return when (method) {
            "session.resume" -> buildJsonObject { put("session_id", "runtime-1") }
            "session.create" -> buildJsonObject {
                put("session_id", "runtime-new")
                put("stored_session_id", "stored-new")
            }
            "session.events.since" -> buildJsonObject {
                put("events", JsonArray(emptyList()))
                put("open_requests", JsonArray(emptyList()))
            }
            "model.options" -> modelOptions()
            else -> JsonObject(emptyMap())
        }
    }

    override fun respond(id: JsonPrimitive, result: JsonObject) = Unit
    override fun reject(request: ServerRequest) = Unit
    override suspend fun messages(storedId: String): List<StoredMessage> = emptyList()

    private fun modelOptions() = buildJsonObject {
        put("provider", "nous")
        put("model", "current-model")
        put(
            "providers",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("slug", "nous")
                        put("name", "Nous Portal")
                        put("authenticated", true)
                        put("models", JsonArray(listOf(JsonPrimitive("configured-nous-model"))))
                    },
                    buildJsonObject {
                        put("slug", "zai")
                        put("name", "Z.AI / GLM")
                        put("authenticated", true)
                        put("models", JsonArray(listOf(JsonPrimitive("glm-5.3"))))
                    },
                ),
            ),
        )
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content
