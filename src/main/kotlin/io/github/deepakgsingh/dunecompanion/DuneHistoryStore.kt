package io.github.deepakgsingh.dunecompanion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Durable, app-private history of finished games. One session id is stored once.
 *
 * The app has no backup and no server, so this file is the only copy: uninstalling the app
 * deletes it. The panel's export is the way to keep it.
 */
class DuneHistoryStore(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, "dune_history.json"))

    fun appendIfNew(record: JSONObject): Boolean = synchronized(LOCK) {
        val games = readGames()
        val sessionId = record.optString("session_id")
        for (index in 0 until games.length()) {
            if (games.optJSONObject(index)?.optString("session_id") == sessionId) return false
        }
        games.put(record)
        write(games)
        true
    }

    /** False when no record has [sessionId]. */
    fun delete(sessionId: String): Boolean = synchronized(LOCK) {
        val games = readGames()
        val kept = JSONArray()
        for (index in 0 until games.length()) {
            val record = games.optJSONObject(index) ?: continue
            if (record.optString("session_id") != sessionId) kept.put(record)
        }
        if (kept.length() == games.length()) return false
        write(kept)
        true
    }

    fun records(): List<JSONObject> = synchronized(LOCK) {
        val games = readGames()
        (0 until games.length()).mapNotNull { games.optJSONObject(it) }
    }

    fun find(sessionId: String): JSONObject? = records().firstOrNull { it.optString("session_id") == sessionId }

    private fun write(games: JSONArray) {
        val updated = JSONObject().put("games", games)
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        temporary.writeText(updated.toString(2), Charsets.UTF_8)
        if (!temporary.renameTo(file)) {
            temporary.delete()
            error("could not atomically replace ${file.name}")
        }
    }

    private fun readGames(): JSONArray = runCatching {
        if (!file.exists()) JSONArray()
        else JSONObject(file.readText(Charsets.UTF_8)).optJSONArray("games") ?: JSONArray()
    }.getOrDefault(JSONArray())

    companion object {
        // One lock for every instance: the service and the panel each hold a store on the same
        // file, and a per-instance lock would let one's write drop the other's.
        private val LOCK = Any()
    }
}

fun duneHistorySessionId(session: DuneSession): String = "game-${session.liveSessionStartedAtMs}"

/**
 * A game as the history keeps it. The same shape the rabbit device sends dubbleg.org
 * (schema 1), less the edition and sharing fields, so the two stay easy to compare.
 */
fun duneHistoryJson(session: DuneSession, nowMs: Long = System.currentTimeMillis()): JSONObject {
    val turns = JSONArray(session.turnRecords.map { record ->
        JSONObject()
            .put("id", record.id)
            .put("round", record.round)
            .put("seat", record.seat.wireName)
            .put("seat_name", record.seatName)
            .put("kind", record.kind.wireName)
            .put("started_at_ms", record.startedAtMs)
            .put("ended_at_ms", record.endedAtMs)
            .put("pause_excluded_duration_ms", record.durationMs)
            .put("completed", true)
    })
    val finished = session.winner != null
    return JSONObject()
        .put("schema_version", 1)
        .put("session_id", duneHistorySessionId(session))
        .put("game_type", "dune-imperium")
        .put("status", if (finished) "finished" else "in-progress")
        .put("finished", finished)
        .put("started_at_ms", session.liveSessionStartedAtMs)
        .put("ended_at_ms", session.endedAtMs ?: 0L)
        .put("players", JSONArray(session.playerColors.map { color ->
            JSONObject().put("seat", color.wireName).put("name", session.seatName(color) ?: color.displayName)
        }))
        .put("winner", session.winner?.wireName ?: "")
        .put("winner_name", session.winner?.let { session.seatName(it) ?: it.displayName } ?: "")
        .put("rounds", session.round)
        .put("elapsed_game_ms", duneGameElapsedMs(session, session.endedAtMs ?: nowMs))
        .put("score_checkpoints", JSONArray(session.scoreCheckpoints.map { checkpoint ->
            JSONObject()
                .put("conflict", checkpoint.conflict)
                .put("captured_at_ms", checkpoint.capturedAtMs)
                .put("victory_points", JSONObject().apply {
                    checkpoint.victoryPoints.forEach { (color, points) -> put(color.wireName, points) }
                })
                .put("final", checkpoint.final)
                .put("winner", checkpoint.winner?.wireName ?: "")
        }))
        .put("turns", turns)
}
