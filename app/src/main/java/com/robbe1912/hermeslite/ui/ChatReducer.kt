package com.robbe1912.hermeslite.ui

import com.robbe1912.hermeslite.gateway.StoredMessage
import com.robbe1912.hermeslite.gateway.bool
import com.robbe1912.hermeslite.gateway.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One line of a thread. */
sealed interface Row {
    data class Message(val role: String, val text: String, val reasoning: String = "", val streaming: Boolean = false) : Row {
        val fromUser: Boolean get() = role == "user"
    }

    data class Tool(val name: String, val detail: String, val done: Boolean = false) : Row
}

data class ClarifyQuestion(val id: String, val text: String, val choices: List<String>, val multiSelect: Boolean)

/** A request from the gateway that waits for the user. */
sealed interface PendingRequest {
    val id: JsonPrimitive

    data class Approval(
        override val id: JsonPrimitive,
        val command: String,
        val description: String,
        val choices: List<String>,
    ) : PendingRequest

    data class Clarify(override val id: JsonPrimitive, val questions: List<ClarifyQuestion>) : PendingRequest
}

/** The state of one thread. [ThreadModel] changes it with pure functions, so it is easy to test. */
data class ThreadState(
    val rows: List<Row> = emptyList(),
    val thinking: String = "",
    val streaming: Boolean = false,
    val pending: PendingRequest? = null,
    val error: String? = null,
    val lastSeq: Long = 0,
    val title: String = "",
)

/**
 * Applies one gateway event to the thread.
 *
 * Event order of one turn on gateway 0.23.3: `message.start`, then `thinking.delta`,
 * `tool.start` / `tool.complete`, `message.delta` many times, then `message.complete` and
 * `session.info` with `running:false`. There is no `message.done`.
 */
internal fun ThreadState.event(type: String, seq: Long?, payload: JsonObject): ThreadState {
    val state = if (seq != null && seq > lastSeq) copy(lastSeq = seq) else this
    return when (type) {
        "message.start" -> state.copy(streaming = true, error = null)
        "message.delta", "message.token" -> state.appendText(payload.str("text").orEmpty())
        "reasoning.delta" -> state.appendReasoning(payload.str("text").orEmpty())
        "reasoning.available" -> state.finishReasoning(payload.str("text").orEmpty())
        "thinking.delta" -> state.copy(thinking = payload.str("text").orEmpty())
        "tool.generating" -> state.tool(payload.str("name").orEmpty(), "preparing")
        "tool.start" -> state.tool(payload.str("name").orEmpty(), payload.str("context") ?: payload.str("args").orEmpty())
        "tool.progress" -> state.tool(payload.str("name").orEmpty(), payload.str("preview").orEmpty())
        "tool.complete" -> state.tool(payload.str("name").orEmpty(), payload.str("result").orEmpty(), done = true)
        "message.complete" -> state.complete(payload.str("text"), payload.str("reasoning"))
        "session.info" -> state.sessionInfo(payload)
        "session.title" -> payload.str("title")?.let { state.copy(title = it) } ?: state
        "status.update" -> payload.str("text")?.takeIf { it.isNotBlank() }?.let { state.copy(thinking = it) } ?: state
        "request.cancel" -> {
            val open = state.pending
            if (open != null && payload.str("id") == open.id.content) state.copy(pending = null) else state
        }
        else -> state
    }
}

internal fun ThreadState.addUser(text: String): ThreadState =
    copy(rows = rows + Row.Message("user", text), streaming = true, error = null)

/** Removes the user row of a message that the gateway did not accept. */
internal fun ThreadState.removeUser(text: String): ThreadState {
    val last = rows.lastOrNull()
    return if (last is Row.Message && last.fromUser && last.text == text) copy(rows = rows.dropLast(1)) else this
}

internal fun historyRows(messages: List<StoredMessage>): List<Row> {
    val rows = ArrayList<Row>(messages.size)
    for (message in messages) {
        if (message.displayKind == "hidden") continue
        when (message.role) {
            "user" -> if (message.text.isNotBlank()) rows += Row.Message("user", message.text)
            "assistant" -> if (message.text.isNotBlank() || message.reasoning.isNotBlank()) {
                rows += Row.Message("assistant", message.text, message.reasoning)
            }
            "tool" -> rows += Row.Tool(message.toolName.ifBlank { "tool" }, message.text, done = true)
        }
    }
    return rows
}

private fun ThreadState.streamingAssistant(): Row.Message? {
    val last = rows.lastOrNull()
    return if (last is Row.Message && !last.fromUser && last.streaming) last else null
}

private fun ThreadState.replaceLast(replacement: Row): ThreadState = copy(rows = rows.dropLast(1) + replacement)

private fun ThreadState.appendText(text: String): ThreadState {
    if (text.isEmpty()) return this
    val last = streamingAssistant()
    return if (last != null) {
        replaceLast(last.copy(text = last.text + text))
    } else {
        copy(rows = rows + Row.Message("assistant", text, streaming = true))
    }
}

private fun ThreadState.appendReasoning(text: String): ThreadState {
    if (text.isEmpty()) return this
    val last = streamingAssistant()
    return if (last != null) {
        replaceLast(last.copy(reasoning = last.reasoning + text))
    } else {
        copy(rows = rows + Row.Message("assistant", "", text, streaming = true))
    }
}

/** The gateway can send the full reasoning text at the end. It replaces the collected pieces. */
private fun ThreadState.finishReasoning(text: String): ThreadState {
    val last = streamingAssistant() ?: return this
    return if (text.length >= last.reasoning.length) replaceLast(last.copy(reasoning = text)) else this
}

private fun ThreadState.complete(text: String?, reasoning: String?): ThreadState {
    val last = streamingAssistant()
    val rows = when {
        last != null -> rows.dropLast(1) + last.copy(
            text = if (text.isNullOrEmpty()) last.text else text,
            reasoning = reasoning ?: last.reasoning,
            streaming = false,
        )
        !text.isNullOrEmpty() -> rows + Row.Message("assistant", text, reasoning.orEmpty())
        else -> rows
    }
    return copy(rows = rows, streaming = false, thinking = "")
}

private fun ThreadState.tool(name: String, detail: String, done: Boolean = false): ThreadState {
    val last = rows.lastOrNull()
    if (last is Row.Tool && last.name == name && !last.done) {
        return replaceLast(last.copy(detail = detail.ifBlank { last.detail }, done = done))
    }
    return copy(rows = rows + Row.Tool(name, detail, done))
}

private fun ThreadState.sessionInfo(payload: JsonObject): ThreadState {
    var state = this
    if (payload.bool("running") == false) state = state.copy(streaming = false, thinking = "")
    payload.str("title")?.takeIf { it.isNotBlank() }?.let { state = state.copy(title = it) }
    return state
}
