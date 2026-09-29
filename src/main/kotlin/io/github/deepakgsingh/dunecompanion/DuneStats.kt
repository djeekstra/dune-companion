package io.github.deepakgsingh.dunecompanion

import org.json.JSONArray
import org.json.JSONObject

/**
 * The numbers the panel's STATS tab shows, computed from a history record ([duneHistoryJson]).
 *
 * Working from the record rather than from [DuneSession] means one code path serves both the
 * game in progress (its record built on the fly) and every stored game, including ones written
 * by an older build. Turn times are the pause-excluded durations; wall-clock spans would count
 * a dinner break against whoever held the turn.
 */
fun duneGameStats(record: JSONObject): JSONObject {
    val players = record.optJSONArray("players").objects()
    val turns = record.optJSONArray("turns").objects().map { turn ->
        StatTurn(
            round = turn.optInt("round"),
            seat = turn.optString("seat"),
            kind = turn.optString("kind"),
            durationMs = turn.optLong("pause_excluded_duration_ms").coerceAtLeast(0L),
        )
    }
    val totalTurnMs = turns.sumOf { it.durationMs }
    val checkpoints = record.optJSONArray("score_checkpoints").objects()
    val finalPoints = checkpoints.lastOrNull()?.optJSONObject("victory_points")
    val winner = record.optString("winner")

    val seats = players.map { player ->
        val seat = player.optString("seat")
        val own = turns.filter { it.seat == seat }
        val ownMs = own.sumOf { it.durationMs }
        JSONObject()
            .put("seat", seat)
            .put("name", player.optString("name"))
            .put("turns", own.size)
            .put("totalMs", ownMs)
            .put("share", share(ownMs, totalTurnMs))
            .put("averageMs", average(ownMs, own.size))
            .put("longestMs", own.maxOfOrNull { it.durationMs } ?: 0L)
            .put("victoryPoints", finalPoints?.takeIf { it.has(seat) }?.optInt(seat) ?: JSONObject.NULL)
            .put("winner", seat == winner)
    }.sortedByDescending { it.optLong("totalMs") }

    val kinds = listOf(DuneTurnKind.AGENT, DuneTurnKind.REVEAL).map { kind ->
        val own = turns.filter { it.kind == kind.wireName }
        val ownMs = own.sumOf { it.durationMs }
        JSONObject()
            .put("kind", kind.wireName)
            .put("turns", own.size)
            .put("totalMs", ownMs)
            .put("share", share(ownMs, totalTurnMs))
            .put("averageMs", average(ownMs, own.size))
    }

    val rounds = turns.groupBy { it.round }.toSortedMap().map { (round, own) ->
        JSONObject()
            .put("round", round)
            .put("turns", own.size)
            .put("turnTimeMs", own.sumOf { it.durationMs })
    }
    val slowestRound = rounds.maxByOrNull { it.optLong("turnTimeMs") }?.optInt("round") ?: 0

    val longest = turns.maxByOrNull { it.durationMs }
    return JSONObject()
        .put("sessionId", record.optString("session_id"))
        .put("finished", record.optBoolean("finished"))
        .put("startedAtMs", record.optLong("started_at_ms"))
        .put("endedAtMs", record.optLong("ended_at_ms"))
        .put("winner", winner)
        .put("winnerName", record.optString("winner_name"))
        .put("rounds", record.optInt("rounds"))
        .put("gameMs", record.optLong("elapsed_game_ms"))
        .put("turns", turns.size)
        .put("turnTimeMs", totalTurnMs)
        .put("averageTurnMs", average(totalTurnMs, turns.size))
        .put("longestTurn", longest?.let {
            JSONObject().put("seat", it.seat).put("round", it.round).put("kind", it.kind).put("durationMs", it.durationMs)
        } ?: JSONObject.NULL)
        .put("seats", JSONArray(seats))
        .put("kinds", JSONArray(kinds))
        .put("roundTimes", JSONArray(rounds))
        .put("slowestRound", slowestRound)
        .put("checkpoints", JSONArray(checkpoints.map { checkpoint ->
            JSONObject()
                .put("conflict", checkpoint.optInt("conflict"))
                .put("final", checkpoint.optBoolean("final"))
                .put("victoryPoints", checkpoint.optJSONObject("victory_points") ?: JSONObject())
        }))
}

/** One line per stored game for the history list, newest first. */
fun duneGameSummaries(records: List<JSONObject>): JSONArray = JSONArray(
    records.sortedByDescending { it.optLong("started_at_ms") }.map { record ->
        JSONObject()
            .put("sessionId", record.optString("session_id"))
            .put("startedAtMs", record.optLong("started_at_ms"))
            .put("gameMs", record.optLong("elapsed_game_ms"))
            .put("rounds", record.optInt("rounds"))
            .put("winner", record.optString("winner"))
            .put("winnerName", record.optString("winner_name"))
            .put("players", JSONArray(record.optJSONArray("players").objects().map { it.optString("name") }))
    }
)

/**
 * Across every stored game, keyed by the name typed for a seat.
 *
 * A seat, not a person: "Nina & Omar" is its own row, because that is who won or lost. Names
 * are matched case- and space-insensitively so "nina" and "Nina " are one row, shown as typed
 * most recently. An unnamed seat is recorded under its colour, which is not a person at all,
 * so colour-only seats are left out.
 */
fun duneAllTimeStats(records: List<JSONObject>): JSONObject {
    val colourNames = DunePlayerColor.entries.map { it.displayName.lowercase() }.toSet()
    val rows = linkedMapOf<String, NameTally>()
    records.sortedBy { it.optLong("started_at_ms") }.forEach { record ->
        val turns = record.optJSONArray("turns").objects()
        val finalPoints = record.optJSONArray("score_checkpoints").objects().lastOrNull()?.optJSONObject("victory_points")
        record.optJSONArray("players").objects().forEach { player ->
            val name = player.optString("name").trim()
            val key = name.lowercase().replace(Regex("\\s+"), " ")
            if (key.isEmpty() || key in colourNames) return@forEach
            val seat = player.optString("seat")
            val tally = rows.getOrPut(key) { NameTally() }
            tally.name = name
            tally.games += 1
            if (record.optString("winner") == seat) tally.wins += 1
            turns.filter { it.optString("seat") == seat }.forEach {
                tally.turns += 1
                tally.turnMs += it.optLong("pause_excluded_duration_ms").coerceAtLeast(0L)
            }
            finalPoints?.takeIf { it.has(seat) }?.let {
                tally.scoredGames += 1
                tally.points += it.optInt(seat)
            }
        }
    }
    val gameMs = records.sumOf { it.optLong("elapsed_game_ms") }
    return JSONObject()
        .put("games", records.size)
        .put("totalGameMs", gameMs)
        .put("averageGameMs", average(gameMs, records.size))
        .put("players", JSONArray(rows.values.sortedWith(
            compareByDescending<NameTally> { it.wins }.thenByDescending { it.games }.thenBy { it.name.lowercase() }
        ).map { tally ->
            JSONObject()
                .put("name", tally.name)
                .put("games", tally.games)
                .put("wins", tally.wins)
                .put("winRate", share(tally.wins.toLong(), tally.games.toLong()))
                .put("turns", tally.turns)
                .put("averageTurnMs", average(tally.turnMs, tally.turns))
                .put("averagePoints", if (tally.scoredGames == 0) JSONObject.NULL else tally.points.toDouble() / tally.scoredGames)
        }))
}

private data class StatTurn(val round: Int, val seat: String, val kind: String, val durationMs: Long)

private class NameTally {
    var name = ""
    var games = 0
    var wins = 0
    var turns = 0
    var turnMs = 0L
    var scoredGames = 0
    var points = 0
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun average(totalMs: Long, count: Int): Long = if (count == 0) 0L else totalMs / count

// A fraction 0..1, rounded to three places so the panel never prints 0.30000000000000004.
private fun share(part: Long, whole: Long): Double =
    if (whole <= 0L) 0.0 else Math.round(part * 1000.0 / whole) / 1000.0
