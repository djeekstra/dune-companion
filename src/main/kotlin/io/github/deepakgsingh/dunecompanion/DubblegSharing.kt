package io.github.deepakgsingh.dunecompanion

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

const val DUBBLEG_BASE = "https://dubbleg.org"
private val DEVICE_ID = Regex("[a-f0-9]{32}")

/** Upload shape is independent of the local archive; sharing never rewrites game identity. */
fun dubblegUploadBody(record: JSONObject, deviceId: String): JSONObject {
    require(DEVICE_ID.matches(deviceId)) { "Invalid phone identity" }
    require(record.optBoolean("finished") && record.optString("winner").isNotEmpty()) { "Only finished games can be shared" }
    require(!record.optBoolean("test_mode") && record.optString("provenance") != "test") { "Test games stay local" }
    val started = record.optLong("started_at_ms")
    require(started > 0) { "Missing game start time" }
    // Explicit allowlist: upload metadata and credentials can never leak from an export.
    val keys = listOf("schema_version", "game_type", "players", "winner", "winner_name", "rounds",
        "elapsed_game_ms", "score_checkpoints", "turns")
    return JSONObject().apply {
        keys.filter { record.has(it) }.forEach { put(it, record.get(it)) }
        put("session_id", "casual-$deviceId-$started")
        put("game_name", "Dune Imperium")
        put("status", "finished")
        put("finished", true)
        put("test_mode", false)
        put("provenance", "casual")
    }
}

sealed interface DubblegUploadResult {
    data class Shared(val url: String) : DubblegUploadResult
    data class Skipped(val note: String) : DubblegUploadResult
    data object Revoked : DubblegUploadResult
    data class Blocked(val note: String) : DubblegUploadResult
    data object Retry : DubblegUploadResult
}

fun parseDubblegUploadResponse(code: Int, body: String): DubblegUploadResult {
    if (code == 401 || code == 403) return DubblegUploadResult.Blocked("Credential rejected. Reconnect this phone, then retry.")
    if (code in 400..499 && code !in listOf(408, 429)) return DubblegUploadResult.Blocked("Game rejected by DubbleG ($code).")
    if (code !in 200..299) return DubblegUploadResult.Retry
    val json = runCatching { JSONObject(body) }.getOrNull() ?: return DubblegUploadResult.Retry
    if (!json.has("url")) return DubblegUploadResult.Retry
    if (json.isNull("url")) {
        if (json.has("skipped")) return DubblegUploadResult.Skipped(json.optString("skipped_note").trim().take(80).ifEmpty { "Not shared" })
        return if (json.optBoolean("revoked")) DubblegUploadResult.Revoked else DubblegUploadResult.Retry
    }
    val url = json.optString("url")
    return if (Regex("https://dubbleg\\.org/g/[A-Za-z0-9_-]+").matches(url)) DubblegUploadResult.Shared(url)
    else DubblegUploadResult.Retry
}

/** Durable outbox. A job contains the approved snapshot, never its authorization credential. */
class DubblegShareStore(private val file: File) {
    fun queue(record: JSONObject, deviceId: String, retryBlocked: Boolean = true) = synchronized(LOCK) {
        val id = record.optString("session_id")
        val jobs = read()
        val existing = jobs.optJSONObject(id)
        if (existing != null) {
            if (!retryBlocked) return@synchronized
            require(existing.optString("deviceId") == deviceId) { "Reconnect the phone that queued this game" }
            if (existing.optString("status") in listOf("shared", "skipped", "revoked", "pending")) return@synchronized
            // Keep exactly the same snapshot and remote identity on retry.
            existing.put("status", "pending").put("note", "Queued for upload").put("nextAttemptAtMs", 0L).put("backoffMs", 2000L)
        } else {
            jobs.put(id, JSONObject().put("deviceId", deviceId).put("body", dubblegUploadBody(record, deviceId))
                .put("status", "pending").put("note", "Queued for upload").put("nextAttemptAtMs", 0L).put("backoffMs", 2000L))
        }
        write(jobs)
    }

    fun queueSavedGame(history: DuneHistoryStore, id: String, deviceId: String) = synchronized(LOCK) {
        val record = history.find(id) ?: error("No such finished game")
        queue(record, deviceId)
    }

    fun syncConsentedGames(history: DuneHistoryStore) = synchronized(LOCK) {
        history.records().forEach { record ->
            val deviceId = record.optString("share_device_id")
            if (DEVICE_ID.matches(deviceId)) queue(record, deviceId, retryBlocked = false)
        }
    }

    fun deleteLocalGame(history: DuneHistoryStore, id: String): Boolean = synchronized(LOCK) {
        val deleted = history.delete(id)
        forget(id)
        deleted
    }

    fun status(id: String): JSONObject = synchronized(LOCK) {
        read().optJSONObject(id)?.let { job ->
            JSONObject().put("status", job.optString("status")).put("note", job.optString("note"))
                .put("url", job.optString("url"))
        } ?: JSONObject().put("status", "local").put("note", "Saved on this phone")
    }

    fun due(deviceId: String, nowMs: Long): Pair<String, JSONObject>? = synchronized(LOCK) {
        val jobs = read()
        jobs.keys().asSequence().mapNotNull { id -> jobs.optJSONObject(id)?.let { id to it } }
            .firstOrNull { (_, job) -> job.optString("deviceId") == deviceId && job.optString("status") == "pending" && job.optLong("nextAttemptAtMs") <= nowMs }
    }

    fun complete(id: String, result: DubblegUploadResult, nowMs: Long) = synchronized(LOCK) {
        val jobs = read()
        val job = jobs.optJSONObject(id) ?: return@synchronized
        when (result) {
            is DubblegUploadResult.Shared -> job.put("status", "shared").put("note", "Shared on DubbleG").put("url", result.url)
            is DubblegUploadResult.Skipped -> job.put("status", "skipped").put("note", result.note)
            DubblegUploadResult.Revoked -> job.put("status", "revoked").put("note", "Link revoked on DubbleG")
            is DubblegUploadResult.Blocked -> {
                job.put("status", "blocked").put("note", result.note)
            }
            DubblegUploadResult.Retry -> {
                val backoff = job.optLong("backoffMs", 2000L).coerceIn(2000L, 60_000L)
                job.put("status", "pending").put("note", "Waiting to retry. Keep the app open with internet access.")
                    .put("nextAttemptAtMs", nowMs + backoff).put("backoffMs", (backoff * 2).coerceAtMost(60_000L))
            }
        }
        write(jobs)
    }

    /** Removing local history cancels queued work; existing server links remain independent. */
    fun forget(id: String) = synchronized(LOCK) { val jobs = read(); jobs.remove(id); write(jobs) }

    private fun read(): JSONObject = if (!file.exists()) JSONObject() else JSONObject(file.readText()).getJSONObject("jobs")
    private fun write(jobs: JSONObject) {
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        temporary.writeText(JSONObject().put("jobs", jobs).toString())
        check(temporary.renameTo(file)) { "Could not save sharing request" }
    }
    companion object { private val LOCK = Any() }
}
