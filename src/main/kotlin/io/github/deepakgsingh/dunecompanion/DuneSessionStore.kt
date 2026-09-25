package io.github.deepakgsingh.dunecompanion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class DuneSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("dune_session", Context.MODE_PRIVATE)

    fun save(session: DuneSession) {
        val json = JSONObject()
            .put("active", session.active)
            .put("colors", JSONArray(session.playerColors.map { it.wireName }))
            .put("playerNames", JSONObject().apply {
                session.playerNames.forEach { (color, names) -> put(color.wireName, JSONArray(names)) }
            })
            .put("first", session.startingFirstPlayer?.wireName)
            .put("round", session.round)
            .put("phase", session.phase.wireName)
            .put("currentPlayer", session.currentPlayer?.wireName)
            .put("revealedPlayers", JSONArray(session.revealedPlayers.map { it.wireName }))
            .put("thirdAgentPlayers", JSONArray(session.thirdAgentPlayers.map { it.wireName }))
            .put("mentatHolder", session.mentatHolder?.wireName)
            .put("highCouncilPlayers", JSONArray(session.highCouncilPlayers.map { it.wireName }))
            .put("scoreCheckpoints", JSONArray(session.scoreCheckpoints.map { checkpoint ->
                JSONObject()
                    .put("conflict", checkpoint.conflict)
                    .put("capturedAtMs", checkpoint.capturedAtMs)
                    .put("victoryPoints", JSONObject().apply { checkpoint.victoryPoints.forEach { (color, points) -> put(color.wireName, points) } })
                    .put("final", checkpoint.final)
                    .put("winner", checkpoint.winner?.wireName)
            }))
            .put("agentTurnsRemaining", JSONObject().apply {
                session.agentTurnsRemaining.forEach { (color, count) -> put(color.wireName, count) }
            })
            .put("turnStartedAtMs", session.turnStartedAtMs)
            .put("turnStartedGameElapsedMs", session.turnStartedGameElapsedMs)
            .put("paused", session.paused)
            .put("pausedAtMs", session.pausedAtMs)
            .put("fiveMinuteWarningPlayed", session.fiveMinuteWarningPlayed)
            .put("sevenMinuteWarningPlayed", session.sevenMinuteWarningPlayed)
            .put("tenMinuteWarningPlayed", session.tenMinuteWarningPlayed)
            .put("phaseStartedAtMs", session.phaseStartedAtMs)
            .put("gameStartedAtMs", session.gameStartedAtMs)
            .put("liveSessionStartedAtMs", session.liveSessionStartedAtMs)
            .put("hourlyGameCueCount", session.hourlyGameCueCount)
            .put("roundStartedAtMs", session.roundStartedAtMs)
            .put("previousRoundStartedAtMs", session.previousRoundStartedAtMs)
            .put("undoAvailable", session.undoAvailable)
            // Persisted so the undo survives the crash or restart that a phantom turn end is
            // most likely to be noticed alongside.
            .put("turnUndo", session.turnUndo?.let { undo ->
                JSONObject()
                    .put("player", undo.player.wireName)
                    .put("agentsSpent", undo.agentsSpent)
                    .put("revealed", undo.revealed)
                    .put("turnStartedAtMs", undo.turnStartedAtMs)
                    .put("phaseStartedAtMs", undo.phaseStartedAtMs)
                    .put("fiveMinuteWarningPlayed", undo.fiveMinuteWarningPlayed)
                    .put("sevenMinuteWarningPlayed", undo.sevenMinuteWarningPlayed)
                    .put("tenMinuteWarningPlayed", undo.tenMinuteWarningPlayed)
                    .put("roundUndoAvailable", undo.roundUndoAvailable)
                    .put("kind", undo.kind.wireName)
                    .put("turnRecordId", undo.turnRecordId)
                    .put("turnStartedGameElapsedMs", undo.turnStartedGameElapsedMs)
            })
            .put("turnRecords", JSONArray(session.turnRecords.map { record ->
                JSONObject()
                    .put("id", record.id)
                    .put("round", record.round)
                    .put("seat", record.seat.wireName)
                    .put("seatName", record.seatName)
                    .put("kind", record.kind.wireName)
                    .put("startedAtMs", record.startedAtMs)
                    .put("endedAtMs", record.endedAtMs)
                    .put("durationMs", record.durationMs)
            }))
            .put("nextTurnRecordId", session.nextTurnRecordId)
            .put("testMode", session.testMode)
            .put("winner", session.winner?.wireName)
            .put("endedAtMs", session.endedAtMs)
            .put("winnerEndsAtMs", session.winnerEndsAtMs)
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    fun load(nowMs: Long = System.currentTimeMillis()): DuneSession {
        val raw = prefs.getString(KEY, null) ?: return DuneSession()
        return runCatching {
            val json = JSONObject(raw)
            val colorsJson = json.optJSONArray("colors") ?: JSONArray()
            val colors = buildList {
                for (i in 0 until colorsJson.length()) {
                    DunePlayerColor.fromWireName(colorsJson.optString(i))?.let(::add)
                }
            }
            val namesJson = json.optJSONObject("playerNames") ?: JSONObject()
            // A seat holds a list now. Sessions written before that hold a bare string, and one
            // of those can be in preferences right now — the store is read on the next launch,
            // not on the next game.
            val names = colors.mapNotNull { color ->
                val seat = namesJson.optJSONArray(color.wireName)?.let { array ->
                    (0 until array.length()).map { array.optString(it).trim() }
                } ?: listOf(namesJson.optString(color.wireName).trim())
                seat.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { color to it }
            }.toMap()
            val revealedJson = json.optJSONArray("revealedPlayers") ?: JSONArray()
            val revealed = buildSet {
                for (i in 0 until revealedJson.length()) {
                    DunePlayerColor.fromWireName(revealedJson.optString(i))?.let(::add)
                }
            }
            val thirdAgentsJson = json.optJSONArray("thirdAgentPlayers") ?: JSONArray()
            val thirdAgents = buildSet {
                for (i in 0 until thirdAgentsJson.length()) {
                    DunePlayerColor.fromWireName(thirdAgentsJson.optString(i))?.let(::add)
                }
            }
            val councilJson = json.optJSONArray("highCouncilPlayers") ?: JSONArray()
            val highCouncil = buildSet {
                for (i in 0 until councilJson.length()) DunePlayerColor.fromWireName(councilJson.optString(i))?.let(::add)
            }
            val checkpointsJson = json.optJSONArray("scoreCheckpoints") ?: JSONArray()
            val checkpoints = buildList {
                for (i in 0 until checkpointsJson.length()) {
                    val item = checkpointsJson.optJSONObject(i) ?: continue
                    val pointsJson = item.optJSONObject("victoryPoints") ?: JSONObject()
                    val points = colors.associateWith { pointsJson.optInt(it.wireName, 0) }
                    add(DuneScoreCheckpoint(
                        conflict = item.optInt("conflict"),
                        capturedAtMs = item.optLong("capturedAtMs"),
                        victoryPoints = points,
                        final = item.optBoolean("final", false),
                        winner = DunePlayerColor.fromWireName(item.optString("winner")),
                    ))
                }
            }
            val remainingJson = json.optJSONObject("agentTurnsRemaining") ?: JSONObject()
            val remaining = colors.associateWith { remainingJson.optInt(it.wireName, 2) }
            // A record naming a player who is no longer seated is dropped rather than
            // repaired — restoring the turn to somebody who left would be worse than no undo.
            val turnUndo = json.optJSONObject("turnUndo")?.let { undoJson ->
                DunePlayerColor.fromWireName(undoJson.optString("player"))
                    ?.takeIf { it in colors }
                    ?.let { player ->
                        DuneTurnUndo(
                            player = player,
                            agentsSpent = undoJson.optInt("agentsSpent", 0),
                            revealed = undoJson.optBoolean("revealed", false),
                            turnStartedAtMs = undoJson.optLong("turnStartedAtMs", 0L),
                            phaseStartedAtMs = undoJson.optLong("phaseStartedAtMs", 0L),
                            fiveMinuteWarningPlayed = undoJson.optBoolean("fiveMinuteWarningPlayed", false),
                            sevenMinuteWarningPlayed = undoJson.optBoolean("sevenMinuteWarningPlayed", false),
                            tenMinuteWarningPlayed = undoJson.optBoolean("tenMinuteWarningPlayed", false),
                            roundUndoAvailable = undoJson.optBoolean("roundUndoAvailable", false),
                            kind = DuneTurnKind.fromWireName(undoJson.optString("kind")) ?: DuneTurnKind.AGENT,
                            turnRecordId = undoJson.optLong("turnRecordId", -1L).takeIf { it >= 0L },
                            turnStartedGameElapsedMs = undoJson.optLong("turnStartedGameElapsedMs", 0L),
                        )
                    }
            }
            val turnRecordsJson = json.optJSONArray("turnRecords") ?: JSONArray()
            val turnRecords = buildList {
                for (i in 0 until turnRecordsJson.length()) {
                    val item = turnRecordsJson.optJSONObject(i) ?: continue
                    val seat = DunePlayerColor.fromWireName(item.optString("seat")) ?: continue
                    val kind = DuneTurnKind.fromWireName(item.optString("kind")) ?: continue
                    add(DuneTurnRecord(
                        id = item.optLong("id", i.toLong() + 1L),
                        round = item.optInt("round", 1),
                        seat = seat,
                        seatName = item.optString("seatName", seat.displayName),
                        kind = kind,
                        startedAtMs = item.optLong("startedAtMs", 0L),
                        endedAtMs = item.optLong("endedAtMs", 0L),
                        durationMs = item.optLong("durationMs", 0L).coerceAtLeast(0L),
                    ))
                }
            }
            val winnerEndsAt = json.optNullableLong("winnerEndsAtMs")
            if (winnerEndsAt != null && winnerEndsAt <= nowMs) return@runCatching DuneSession()
            val round = json.optInt("round", 1).coerceIn(1, 10)
            val first = DunePlayerColor.fromWireName(json.optString("first"))
            val migratedCurrentPlayer = first?.let { startingColor ->
                val index = colors.indexOf(startingColor)
                if (index >= 0 && colors.isNotEmpty()) colors[(index + round - 1) % colors.size] else null
            }
            val phase = DunePhase.fromWireName(json.optString("phase")) ?: DunePhase.TURNS
            val currentPlayer = DunePlayerColor.fromWireName(json.optString("currentPlayer"))
                ?: migratedCurrentPlayer.takeIf { phase == DunePhase.TURNS }
            DuneSession(
                active = json.optBoolean("active", false),
                playerColors = colors,
                playerNames = names,
                startingFirstPlayer = first,
                round = round,
                phase = phase,
                currentPlayer = currentPlayer,
                revealedPlayers = revealed,
                thirdAgentPlayers = thirdAgents,
                mentatHolder = DunePlayerColor.fromWireName(json.optString("mentatHolder")),
                highCouncilPlayers = highCouncil,
                scoreCheckpoints = checkpoints,
                agentTurnsRemaining = remaining,
                turnStartedAtMs = json.optLong("turnStartedAtMs", json.optLong("roundStartedAtMs", 0L)),
                turnStartedGameElapsedMs = json.optLong("turnStartedGameElapsedMs", 0L),
                paused = json.optBoolean("paused", false),
                pausedAtMs = json.optNullableLong("pausedAtMs"),
                fiveMinuteWarningPlayed = json.optBoolean("fiveMinuteWarningPlayed", false),
                sevenMinuteWarningPlayed = json.optBoolean("sevenMinuteWarningPlayed", false),
                tenMinuteWarningPlayed = json.optBoolean("tenMinuteWarningPlayed", false),
                phaseStartedAtMs = json.optLong("phaseStartedAtMs", json.optLong("roundStartedAtMs", 0L)),
                gameStartedAtMs = json.optLong("gameStartedAtMs", 0L),
                // Older sessions have no separate public identity. Their current elapsed-time
                // clock is the only available identity, and is already stable after migration.
                liveSessionStartedAtMs = json.optLong(
                    "liveSessionStartedAtMs",
                    json.optLong("gameStartedAtMs", 0L),
                ),
                hourlyGameCueCount = json.optInt("hourlyGameCueCount", 0).coerceAtLeast(0),
                roundStartedAtMs = json.optLong("roundStartedAtMs", 0L),
                previousRoundStartedAtMs = json.optNullableLong("previousRoundStartedAtMs"),
                undoAvailable = json.optBoolean("undoAvailable", false),
                turnUndo = turnUndo,
                turnRecords = turnRecords,
                nextTurnRecordId = json.optLong("nextTurnRecordId", (turnRecords.maxOfOrNull { it.id } ?: 0L) + 1L),
                testMode = json.optBoolean("testMode", false),
                winner = DunePlayerColor.fromWireName(json.optString("winner")),
                endedAtMs = json.optNullableLong("endedAtMs"),
                winnerEndsAtMs = winnerEndsAt,
            )
        }.getOrDefault(DuneSession())
    }

    private fun JSONObject.optNullableLong(name: String): Long? =
        if (has(name) && !isNull(name)) optLong(name) else null

    companion object { private const val KEY = "session" }
}
