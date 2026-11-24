package com.robbe1912.hermeslite.gateway

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.OffsetDateTime

// The gateway sends numbers as JSON numbers and times as epoch floats. These helpers read one
// field of a JSON object and return null for a missing field or for null.

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

internal fun JsonObject.number(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull

internal fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonArray.strings(): List<String> = map { (it as? JsonPrimitive)?.content ?: "" }

internal fun JsonObject.texts(key: String): List<String> = arr(key)?.strings() ?: emptyList()

/** One row of the channels list. */
data class ChannelRow(
    val id: String,
    val title: String,
    val preview: String,
    val startedAt: Long,
    val lastActivityAt: Long,
    val messageCount: Int,
    val source: String,
)

internal fun JsonObject.toChannelRow() = ChannelRow(
    id = str("id").orEmpty(),
    title = str("title") ?: str("display_name") ?: str("preview")?.take(60) ?: "Untitled",
    preview = str("preview").orEmpty(),
    startedAt = (number("started_at") ?: 0.0).toLong(),
    lastActivityAt = (number("last_activity_at") ?: 0.0).toLong(),
    messageCount = (number("message_count") ?: 0.0).toInt(),
    source = str("source").orEmpty(),
)

/** One stored message of a thread. */
data class StoredMessage(val role: String, val text: String, val reasoning: String, val toolName: String)

internal fun JsonObject.toStoredMessage() = StoredMessage(
    role = str("role").orEmpty(),
    text = str("content").orEmpty(),
    reasoning = str("reasoning") ?: str("reasoning_content") ?: "",
    toolName = str("tool_name").orEmpty(),
)

/** One cron job of the gateway. Times are epoch seconds, or 0 when the gateway sent none. */
data class CronJob(
    val id: String,
    val name: String,
    val schedule: String,
    val enabled: Boolean,
    /** A job without an agent runs a script and writes a report instead of a thread. */
    val scriptOnly: Boolean,
    val running: Boolean,
    /** The status of the last run: "ok", "error", or "" before the first run. */
    val lastStatus: String,
    val lastError: String,
    val lastRunAt: Long,
    val nextRunAt: Long,
)

internal fun JsonObject.toCronJob() = CronJob(
    id = str("id").orEmpty(),
    name = str("name") ?: "Untitled",
    schedule = str("schedule_display").orEmpty(),
    enabled = bool("enabled") ?: true,
    scriptOnly = bool("no_agent") ?: false,
    running = obj("live_execution") != null || obj("latest_execution")?.str("status") == "running",
    lastStatus = str("last_status").orEmpty(),
    lastError = str("last_error").orEmpty(),
    lastRunAt = parseTime(str("last_run_at")),
    nextRunAt = parseTime(str("next_run_at")),
)

/** One run of a cron job. A run with a session is a thread that the user can reply in. */
data class CronRun(
    val id: String,
    val title: String,
    val preview: String,
    val startedAt: Long,
    /** 0 while the run is active. */
    val endedAt: Long,
    val endReason: String,
    val messageCount: Int,
    /** The report path of a script run. It is "" for a run with a session. */
    val reportPath: String,
) {
    val running: Boolean get() = endedAt <= 0L && reportPath.isEmpty()
}

internal fun JsonObject.toCronRun() = CronRun(
    id = str("id").orEmpty(),
    title = str("title") ?: str("display_name") ?: "",
    preview = str("preview").orEmpty(),
    startedAt = (number("started_at") ?: 0.0).toLong(),
    endedAt = (number("ended_at") ?: 0.0).toLong(),
    endReason = str("end_reason").orEmpty(),
    messageCount = (number("message_count") ?: 0.0).toInt(),
    reportPath = reportPath(str("id").orEmpty()),
)

/** The report path of a script run for the files API of the gateway. */
internal fun reportPath(runId: String): String {
    // cron_output:<job id>:<stamp> is the report cron/output/<job id>/<stamp>.md
    val rest = runId.removePrefix("cron_output:")
    if (rest == runId) return ""
    val colon = rest.indexOf(':')
    if (colon <= 0 || colon == rest.length - 1) return ""
    return "cron/output/${rest.substring(0, colon)}/${rest.substring(colon + 1)}.md"
}

/** Reads an ISO time or epoch seconds. Returns 0 when the value is missing or unknown. */
internal fun parseTime(value: String?): Long {
    if (value.isNullOrBlank()) return 0
    value.toDoubleOrNull()?.let { return it.toLong() }
    return runCatching { OffsetDateTime.parse(value).toInstant().epochSecond }
        .recoverCatching { Instant.parse(value).epochSecond }
        .getOrDefault(0)
}
