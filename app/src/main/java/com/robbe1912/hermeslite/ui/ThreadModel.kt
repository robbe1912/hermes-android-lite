package com.robbe1912.hermeslite.ui

import com.robbe1912.hermeslite.gateway.GatewayApi
import com.robbe1912.hermeslite.gateway.GatewayEvent
import com.robbe1912.hermeslite.gateway.GatewayException
import com.robbe1912.hermeslite.gateway.GatewaySocket
import com.robbe1912.hermeslite.gateway.ServerRequest
import com.robbe1912.hermeslite.gateway.StoredMessage
import com.robbe1912.hermeslite.gateway.arr
import com.robbe1912.hermeslite.gateway.bool
import com.robbe1912.hermeslite.gateway.number
import com.robbe1912.hermeslite.gateway.obj
import com.robbe1912.hermeslite.gateway.str
import com.robbe1912.hermeslite.gateway.texts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal interface ThreadBackend {
    val connected: StateFlow<Boolean>
    val events: SharedFlow<GatewayEvent>
    val requests: SharedFlow<ServerRequest>
    suspend fun ensureConnected()
    suspend fun call(method: String, params: JsonObject): JsonObject
    fun respond(id: JsonPrimitive, result: JsonObject)
    fun reject(request: ServerRequest)
    suspend fun messages(storedId: String): List<StoredMessage>
}

private class GatewayThreadBackend(private val api: GatewayApi, private val socket: GatewaySocket) : ThreadBackend {
    override val connected: StateFlow<Boolean> get() = socket.connected
    override val events: SharedFlow<GatewayEvent> get() = socket.events
    override val requests: SharedFlow<ServerRequest> get() = socket.requests
    override suspend fun ensureConnected() = socket.ensureConnected()
    override suspend fun call(method: String, params: JsonObject) = socket.call(method, params)
    override fun respond(id: JsonPrimitive, result: JsonObject) = socket.respond(id, result)
    override fun reject(request: ServerRequest) = socket.reject(request)
    override suspend fun messages(storedId: String) = api.messages(storedId)
}

internal data class ThreadModelOption(val provider: String, val providerName: String, val model: String)

private data class ThreadModelCatalog(val options: List<ThreadModelOption>, val current: ThreadModelOption?)

private fun parseThreadModelCatalog(result: JsonObject): ThreadModelCatalog {
    val providerNames = mutableMapOf<String, String>()
    val options = mutableListOf<ThreadModelOption>()
    result.arr("providers")?.forEach { value ->
        val provider = value as? JsonObject ?: return@forEach
        val slug = provider.str("slug") ?: return@forEach
        val name = provider.str("name") ?: slug
        providerNames[slug] = name
        if (provider.bool("authenticated") != true) return@forEach
        provider.arr("models")?.forEach { modelValue ->
            val model = (modelValue as? JsonPrimitive)?.content ?: return@forEach
            options += ThreadModelOption(slug, name, model)
        }
    }
    val current = result.str("provider")?.let { provider ->
        result.str("model")?.let { model ->
            ThreadModelOption(provider, providerNames[provider] ?: provider, model)
        }
    }
    return ThreadModelCatalog(options.distinct(), current)
}

/** The thread models of the app, one for each stored thread. */
class Threads(private val api: GatewayApi, private val socket: GatewaySocket, private val scope: CoroutineScope, private val onAnswer: (String) -> Unit = {}) {
    private val backend = GatewayThreadBackend(api, socket)
    private val models = mutableMapOf<String, ThreadModel>()

    /** The key of a thread that has no stored id yet. */
    private val newKey = "new"

    @Synchronized
    fun get(storedId: String?): ThreadModel =
        models.getOrPut(storedId ?: newKey) { ThreadModel(storedId, backend, scope, onAnswer) }

    /** Moves the model of a new thread to the key of its stored id. */
    @Synchronized
    fun adopt(storedId: String) {
        val model = models.remove(newKey) ?: return
        models[storedId] = model
    }
}

/**
 * One thread: its rows, the text in the composer, and the link to the gateway session.
 *
 * The model lives in the app scope. It follows the gateway events also when the screen is closed,
 * so an open thread is current when the user comes back.
 */
class ThreadModel internal constructor(
    val storedId: String?,
    private val backend: ThreadBackend,
    private val scope: CoroutineScope,
    private val onAnswer: (String) -> Unit,
) {
    val state = MutableStateFlow(ThreadState())
    val loading = MutableStateFlow(false)
    internal val availableModels = MutableStateFlow<List<ThreadModelOption>>(emptyList())
    internal val selectedModel = MutableStateFlow<ThreadModelOption?>(null)
    internal val modelsLoading = MutableStateFlow(false)
    internal val modelError = MutableStateFlow<String?>(null)

    /** The text in the composer. It stays when the user leaves the screen. */
    val input = MutableStateFlow("")

    /** The stored id. It is known from the start, or after the first message. */
    var realStoredId: String? = storedId
        private set

    private var runtimeId: String? = null
    private var pendingRequest: ServerRequest? = null
    private var job: Job? = null
    private var appliedModel: ThreadModelOption? = null

    /** Loads the history and follows the gateway events. [onCreated] gets the id of a new thread. */
    fun open(onCreated: (String) -> Unit = {}) {
        if (job?.isActive == true) return
        job = scope.launch {
            launch {
                backend.connected.collect { on -> if (on) attempt { attach() } }
            }
            launch {
                backend.events.collect { event -> onEvent(event) }
            }
            launch {
                backend.requests.collect { request -> onRequest(request) }
            }
            realStoredId?.let { attempt { loadHistory(it) } }
            attempt { backend.ensureConnected() }
            if (realStoredId == null) attempt { loadModelOptions() }
        }
    }

    internal fun selectModel(option: ThreadModelOption) {
        if (option in availableModels.value) {
            selectedModel.value = option
            modelError.value = null
        }
    }

    internal fun refreshModels() {
        scope.launch { loadModelOptions() }
    }

    /** Sends the text. A new thread is created with the first message. */
    fun send(onCreated: (String) -> Unit = {}) {
        val text = input.value.trim()
        if (text.isEmpty() || state.value.pending != null) return
        val choice = selectedModel.value
        input.value = ""
        state.update { it.addUser(text) }
        scope.launch {
            try {
                val existingRuntime = runtimeId
                val runtime = existingRuntime ?: create(onCreated, choice)
                if (existingRuntime != null && choice != null && choice != appliedModel) {
                    backend.call(
                        "command.dispatch",
                        buildJsonObject {
                            put("session_id", runtime)
                            put("name", "model")
                            put("arg", "${choice.provider}:${choice.model}")
                        },
                    )
                    appliedModel = choice
                }
                backend.call(
                    "prompt.submit",
                    buildJsonObject {
                        put("session_id", runtime)
                        put("text", text)
                    },
                )
                state.update { it.copy(streaming = true) }
            } catch (e: CancellationException) {
                state.update { it.removeUser(text) }
                throw e
            } catch (e: Exception) {
                state.update { it.removeUser(text).copy(streaming = false, error = userMessage(e)) }
                input.value = text
            }
        }
    }

    /** Stops the turn that runs now. */
    fun stop() {
        val runtime = runtimeId ?: return
        scope.launch {
            attempt {
                backend.call("session.interrupt", buildJsonObject { put("session_id", runtime) })
                state.update { it.copy(streaming = false, thinking = "", pending = null) }
            }
        }
    }

    /** Answers the open request with [result]. */
    fun answer(result: JsonObject) {
        val pending = state.value.pending ?: return
        backend.respond(pending.id, result)
        state.update { it.copy(pending = null) }
    }

    /** Refuses the open request: it denies an approval, or it cancels the questions. */
    fun decline() {
        val pending = state.value.pending ?: return
        val result = if (pending is PendingRequest.Approval) buildJsonObject { put("choice", "deny") } else JsonObject(emptyMap())
        answer(result)
    }

    private suspend fun create(onCreated: (String) -> Unit, choice: ThreadModelOption?): String {
        val result = backend.call(
            "session.create",
            buildJsonObject {
                put("source", "gui")
                choice?.let {
                    put("provider", it.provider)
                    put("model", it.model)
                }
            },
        )
        val runtime = result.str("session_id") ?: throw GatewayException("The gateway gave no session id.")
        val stored = result.str("stored_session_id")
        runtimeId = runtime
        appliedModel = choice
        if (stored != null && stored != realStoredId) {
            realStoredId = stored
            onCreated(stored)
        }
        return runtime
    }

    /** Attaches the socket to the thread and applies the events that this app missed. */
    private suspend fun attach() {
        val stored = realStoredId ?: return
        val result = backend.call(
            "session.resume",
            buildJsonObject {
                put("session_id", stored)
                put("source", "desktop")
                put("omit_messages", true)
            },
        )
        val runtime = result.str("session_id") ?: throw GatewayException("The gateway gave no session id.")
        runtimeId = runtime
        if (result.bool("running") == true) state.update { it.copy(streaming = true) }
        replay(runtime)
        loadModelOptions(runtime)
    }

    private suspend fun replay(runtime: String) {
        // An older gateway has no replay. Then the history shows the stored state.
        val result = runCatching {
            backend.call(
                "session.events.since",
                buildJsonObject {
                    put("session_id", runtime)
                    put("last_seen", state.value.lastSeq)
                },
            )
        }.getOrNull() ?: return
        val stored = realStoredId
        if (result.bool("truncated") == true && stored != null) {
            loadHistory(stored)
            return
        }
        result.arr("events")?.forEach { element ->
            val params = element as? JsonObject ?: return@forEach
            val type = params.str("type") ?: return@forEach
            state.update { it.event(type, params.number("seq")?.toLong(), params.obj("payload") ?: JsonObject(emptyMap())) }
        }
        result.arr("open_requests")?.forEach { element -> restoreRequest(element as? JsonObject) }
    }

    /** A request that was open before the socket dropped is still open. Show it again. */
    private fun restoreRequest(snapshot: JsonObject?) {
        if (snapshot == null || snapshot.str("session_id") != runtimeId) return
        val id = snapshot["id"] as? JsonPrimitive ?: return
        val method = snapshot.str("method") ?: return
        val request = ServerRequest(id, method, snapshot)
        val pending = parseRequest(request) ?: return
        pendingRequest = request
        state.update { it.copy(pending = pending) }
    }

    private suspend fun loadModelOptions(sessionId: String? = runtimeId) {
        modelsLoading.value = true
        modelError.value = null
        try {
            val result = backend.call(
                "model.options",
                buildJsonObject { sessionId?.let { put("session_id", it) } },
            )
            val catalog = parseThreadModelCatalog(result)
            availableModels.value = catalog.options
            val current = catalog.current
            if (current != null) {
                if (selectedModel.value == null || selectedModel.value == appliedModel) {
                    selectedModel.value = current
                }
                appliedModel = current
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            modelError.value = userMessage(e)
        } finally {
            modelsLoading.value = false
        }
    }

    private suspend fun loadHistory(storedId: String) {
        loading.value = true
        try {
            val rows = historyRows(backend.messages(storedId))
            state.update { it.copy(rows = rows, streaming = false, thinking = "", pending = null) }
        } finally {
            loading.value = false
        }
    }

    private fun onEvent(event: GatewayEvent) {
        val runtime = runtimeId ?: return
        if (event.sessionId != runtime) return
        state.update { it.event(event.type, event.seq, event.payload) }
        // A finished answer goes to the voice of the device when the user turned it on.
        if (event.type == "message.complete") {
            event.payload.str("text")?.takeIf { it.isNotBlank() }?.let(onAnswer)
        }
    }

    private fun onRequest(request: ServerRequest) {
        if (request.params.str("session_id") != runtimeId) {
            backend.reject(request)
            return
        }
        val pending = parseRequest(request)
        if (pending == null) {
            backend.reject(request)
            return
        }
        pendingRequest = request
        state.update { it.copy(pending = pending) }
    }

    /** Reports an error in the thread. A cancelled coroutine stays cancelled. */
    private suspend fun attempt(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.update { it.copy(error = userMessage(e)) }
        }
    }
}

/** Reads the requests that this app can answer: an approval or a batch of questions. */
internal fun parseRequest(request: ServerRequest): PendingRequest? = when (request.method) {
    "approval" -> PendingRequest.Approval(
        id = request.id,
        command = request.params.str("command").orEmpty(),
        description = request.params.str("description").orEmpty(),
        choices = request.params.texts("choices").ifEmpty { listOf("once", "deny") },
    )
    "clarify" -> {
        val questions = request.params.arr("questions")?.mapNotNull { frame ->
            val question = frame as? JsonObject ?: return@mapNotNull null
            val id = question.str("qid") ?: question.str("question_id") ?: return@mapNotNull null
            ClarifyQuestion(id, question.str("question").orEmpty(), question.texts("choices"), question.bool("multi_select") == true)
        } ?: emptyList()
        if (questions.isEmpty()) null else PendingRequest.Clarify(request.id, questions)
    }
    else -> null
}
