package com.robbe1912.hermeslite.ui

import com.robbe1912.hermeslite.gateway.StoredMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The events here are the ones that gateway 0.23.3 sent in a live turn. */
class ChatReducerTest {
    private fun event(type: String, seq: Long? = null, vararg fields: Pair<String, String?>): ThreadState =
        ThreadState().event(type, seq, buildJsonObject { fields.forEach { (key, value) -> put(key, value) } })

    private fun ThreadState.ev(type: String, vararg fields: Pair<String, String?>): ThreadState =
        event(type, lastSeq + 1, buildJsonObject { fields.forEach { (key, value) -> put(key, value) } })

    private fun toolTurn(): ThreadState {
        var state = event("message.start", 2, "turn_id" to "t1")
        state = state.ev("thinking.delta", "text" to "(o_o) reflecting...")
        state = state.ev("tool.generating", "name" to "terminal")
        state = state.ev("tool.start", "name" to "terminal", "args" to "{\"command\":\"echo HI\"}")
        state = state.ev("tool.complete", "name" to "terminal", "result" to "{\"output\":\"HI\"}")
        state = state.ev("message.delta", "text" to "H", "turn_id" to "t1")
        state = state.ev("message.delta", "text" to "I", "turn_id" to "t1")
        return state
    }

    @Test
    fun aTurnWithAToolCallEndsAsOneToolRowAndOneMessage() {
        var state = toolTurn()

        assertTrue(state.streaming)
        assertEquals("HI", (state.rows.last() as Row.Message).text)

        state = state.ev("message.complete", "text" to "HI")
        assertFalse(state.streaming)

        val tool = state.rows[0] as Row.Tool
        val answer = state.rows[1] as Row.Message
        assertTrue(tool.done)
        assertEquals("terminal", tool.name)
        assertEquals("{\"output\":\"HI\"}", tool.detail)
        assertEquals("HI", answer.text)
        assertFalse(answer.streaming)
        assertEquals(2, state.rows.size)
    }

    @Test
    fun theFinalTextReplacesTheStreamedPieces() {
        val state = toolTurn().ev("message.complete", "text" to " HI ")
        assertEquals(" HI ", (state.rows.last() as Row.Message).text)
    }

    @Test
    fun textBeforeAToolCallStaysItsOwnMessage() {
        var state = event("message.start", 2).ev("message.delta", "text" to "Let me check.")
        state = state.ev("tool.start", "name" to "terminal").ev("tool.complete", "name" to "terminal", "result" to "ok")
        state = state.ev("message.delta", "text" to "Done.").ev("message.complete", "text" to "Done.")

        assertEquals(listOf("Let me check.", "Done."), state.rows.filterIsInstance<Row.Message>().map { it.text })
        assertEquals(1, state.rows.count { it is Row.Tool })
    }

    @Test
    fun anIdleSessionInfoEndsTheTurnAndAClearsTheStatusLine() {
        var state = event("message.start", 2).ev("thinking.delta", "text" to "working")
        state = state.ev("session.info", "running" to "false", "title" to "New title")

        assertFalse(state.streaming)
        assertEquals("", state.thinking)
        assertEquals("New title", state.title)
    }

    @Test
    fun theSequenceNumberOnlyGrowsAndACancelClearsTheRequest() {
        val id = kotlinx.serialization.json.JsonPrimitive("srq-1")
        val pending = PendingRequest.Approval(id, "rm -rf x", "", listOf("once", "deny"))
        var state = ThreadState(pending = pending).event("session.title", 7, JsonObject(emptyMap()))
        state = state.event("session.title", 3, JsonObject(emptyMap()))
        assertEquals(7L, state.lastSeq)

        assertEquals(pending, state.ev("request.cancel", "id" to "srq-2").pending)
        assertEquals(null, state.ev("request.cancel", "id" to "srq-1").pending)
    }

    @Test
    fun historyRowsSkipEmptyAssistantRowsAndKeepToolRows() {
        val rows = historyRows(
            listOf(
                StoredMessage("user", "hi", "", ""),
                StoredMessage("assistant", "", "", ""),
                StoredMessage("tool", "{\"output\":\"1\"}", "", "terminal"),
                StoredMessage("assistant", "done", "because", ""),
                StoredMessage("system", "ignored", "", ""),
            ),
        )
        assertEquals(3, rows.size)
        assertEquals("terminal", (rows[1] as Row.Tool).name)
        assertEquals("because", (rows[2] as Row.Message).reasoning)
    }
}
