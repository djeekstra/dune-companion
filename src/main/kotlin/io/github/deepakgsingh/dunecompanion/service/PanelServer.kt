package io.github.deepakgsingh.dunecompanion.service

import io.github.deepakgsingh.dunecompanion.ClipSpec
import io.github.deepakgsingh.dunecompanion.Config
import io.github.deepakgsingh.dunecompanion.DUNE_CUE_BOOST
import io.github.deepakgsingh.dunecompanion.DUNE_REVEAL_END_CUE
import io.github.deepakgsingh.dunecompanion.DUNE_TURN_END_CUE
import io.github.deepakgsingh.dunecompanion.DuneActionResult
import io.github.deepakgsingh.dunecompanion.DubblegConnection
import io.github.deepakgsingh.dunecompanion.DubblegShareStore
import io.github.deepakgsingh.dunecompanion.DuneHistoryStore
import io.github.deepakgsingh.dunecompanion.DunePhase
import io.github.deepakgsingh.dunecompanion.DunePlayerColor
import io.github.deepakgsingh.dunecompanion.DuneSession
import io.github.deepakgsingh.dunecompanion.Steward
import io.github.deepakgsingh.dunecompanion.advanceDunePhase
import io.github.deepakgsingh.dunecompanion.advanceDuneSession
import io.github.deepakgsingh.dunecompanion.duneCheckpointDueClip
import io.github.deepakgsingh.dunecompanion.duneConflictClips
import io.github.deepakgsingh.dunecompanion.duneAllTimeStats
import io.github.deepakgsingh.dunecompanion.duneAutoAdvance
import io.github.deepakgsingh.dunecompanion.duneGameElapsedMs
import io.github.deepakgsingh.dunecompanion.duneGameStats
import io.github.deepakgsingh.dunecompanion.duneGameSummaries
import io.github.deepakgsingh.dunecompanion.duneHistoryJson
import io.github.deepakgsingh.dunecompanion.dunePhaseCueFilename
import io.github.deepakgsingh.dunecompanion.duneTurnRestoredClips
import io.github.deepakgsingh.dunecompanion.duneWinnerCueFilename
import io.github.deepakgsingh.dunecompanion.endDuneAgentTurn
import io.github.deepakgsingh.dunecompanion.endDuneRound
import io.github.deepakgsingh.dunecompanion.endDuneSession
import io.github.deepakgsingh.dunecompanion.finishDuneRevealTurn
import io.github.deepakgsingh.dunecompanion.recordDuneScoreCheckpoint
import io.github.deepakgsingh.dunecompanion.startDuneSession
import io.github.deepakgsingh.dunecompanion.toggleDuneHighCouncil
import io.github.deepakgsingh.dunecompanion.toggleDuneMentat
import io.github.deepakgsingh.dunecompanion.toggleDunePause
import io.github.deepakgsingh.dunecompanion.toggleDuneThirdAgent
import io.github.deepakgsingh.dunecompanion.undoDuneRound
import io.github.deepakgsingh.dunecompanion.undoDuneTurn
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The admin panel, served from the display phone to any browser on the same network. The
 * page itself lives in assets/panel.html.
 */
class PanelServer(
    private val clipsDir: File,
    private val historyStore: DuneHistoryStore,
    private val panelHtml: String,
    private val dubblegConnection: DubblegConnection,
    private val shareStore: DubblegShareStore,
) : NanoHTTPD(Config.PANEL_PORT) {

    override fun serve(session: IHTTPSession): Response {
        if (session.uri == "/stats/connect") {
            if (session.method != Method.POST || session.headers["x-dune-panel"] != "1") {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Panel POST required")
            }
            Steward.showDubblegSettings()
            return ok()
        }
        if (session.uri == "/stats/share") {
            // A same-origin custom header prevents a third-party page from queuing uploads.
            if (session.method != Method.POST || session.headers["x-dune-panel"] != "1") {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Panel POST required")
            }
            return try {
                val id = session.parms["id"].orEmpty()
                val credential = dubblegConnection.credential() ?: return error("Connect DubbleG on the display phone first")
                shareStore.queueSavedGame(historyStore, id, credential.deviceId)
                json(shareStore.status(id))
            } catch (failure: Exception) { error(failure.message ?: "Could not queue sharing request") }
        }
        if (session.method != Method.GET) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "GET only")
        }
        val now = System.currentTimeMillis()
        return when (session.uri) {
            "/", "/dune" -> newFixedLengthResponse(Response.Status.OK, "text/html", panelHtml)
            "/dune/state" -> json(duneJson(Steward.dune.value))
            "/dune/start" -> startDune(session, now)
            "/dune/agent-done" -> applyDuneAction(playCue = false, turnEndCue = DUNE_TURN_END_CUE) {
                endDuneAgentTurn(it, now)
            }
            "/dune/reveal-done" -> applyDuneAction(turnEndCue = DUNE_REVEAL_END_CUE) {
                finishDuneRevealTurn(it, now)
            }
            "/dune/swordmaster" -> toggleFor(session, "dune_third_agent_", { p, s -> p !in s.thirdAgentPlayers }) { s, p -> toggleDuneThirdAgent(s, p) }
            "/dune/mentat" -> toggleFor(session, "dune_mentat_", { p, s -> s.mentatHolder != p }) { s, p -> toggleDuneMentat(s, p) }
            "/dune/high-council" -> toggleFor(session, "dune_high_council_", { p, s -> p !in s.highCouncilPlayers }) { s, p -> toggleDuneHighCouncil(s, p) }
            "/dune/checkpoint" -> recordCheckpoint(session, now)
            "/dune/phase-next" -> applyDuneAction { advanceDunePhase(it, now) }
            "/dune/next" -> applyDuneAction { advanceDuneSession(it, now) }
            "/dune/end-round" -> applyDuneAction { endDuneRound(it, now) }
            "/dune/pause" -> pauseDune(now)
            "/dune/undo" -> applyDuneAction(playCue = false) { undoDuneRound(it) }
            "/dune/undo-turn" -> applyDuneAction(
                playCue = false,
                cues = { previous -> previous.turnUndo?.let { duneTurnRestoredClips(previous, it.player) }.orEmpty() },
            ) { undoDuneTurn(it) }
            "/dune/end-game" -> withDuneLock {
                // The service archives a won game as it saves, but a reset landing between two
                // saves would skip it; archiving here too costs nothing, it is once per game.
                Steward.dune.value.takeIf { it.winner != null }?.let { historyStore.appendIfNew(duneHistoryJson(it)) }
                Steward.postDune(DuneSession())
                ok()
            }
            "/dune/end" -> {
                val winner = DunePlayerColor.fromWireName(session.parms["winner"])
                    ?: return error("unknown winner color")
                applyDuneAction { endDuneSession(it, winner, now, Config.DUNE_WINNER_TTL_MS) }
            }
            "/dune/replay" -> replayDuneCue()
            "/audio/reset" -> { Steward.requestAudioReset(); ok() }
            "/audio/test" -> {
                // Bare names only — never let a query param walk out of the clips dir.
                val clip = session.parms["clip"]
                    ?.takeIf { it.isNotBlank() && !it.contains('/') && !it.contains("..") }
                    ?: "steward_speaker_test"
                Steward.emitAudioClips(listOf(ClipSpec(clip, boostMillibels = 600)))
                ok()
            }
            "/stats/connection" -> {
                val credential = dubblegConnection.credential()
                json(JSONObject().put("connected", credential != null).put("name", credential?.name.orEmpty()))
            }
            "/stats/share-status" -> json(shareStore.status(session.parms["id"].orEmpty()))
            "/stats/current" -> {
                val dune = Steward.dune.value
                if (!dune.active) json(JSONObject().put("active", false))
                else json(duneGameStats(duneHistoryJson(dune, now)).put("active", true))
            }
            "/stats/games" -> json(JSONObject().put("games", duneGameSummaries(historyStore.records())))
            "/stats/game" -> historyStore.find(session.parms["id"].orEmpty())
                ?.let { json(duneGameStats(it)) }
                ?: newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", JSONObject().put("error", "no such game").toString())
            "/stats/all-time" -> json(duneAllTimeStats(historyStore.records()))
            "/stats/delete" -> {
                val id = session.parms["id"].orEmpty()
                if (shareStore.deleteLocalGame(historyStore, id)) ok() else error("no such game")
            }
            "/stats/export" -> exportHistory(now)
            "/debug/state" -> json(gestureJson())
            "/debug/reset-counts" -> {
                Steward.updateGestures { it.copy(spins = 0, spinsRejected = 0, shakes = 0, taps = 0, glyphs = 0) }
                ok()
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
        }
    }

    private fun startDune(session: IHTTPSession, nowMs: Long): Response {
        val requested = session.parms["colors"]?.split(',').orEmpty()
        val colors = requested.mapNotNull(DunePlayerColor::fromWireName)
        if (colors.size != requested.size) return error("unknown player color")
        val first = DunePlayerColor.fromWireName(session.parms["first"])
            ?: return error("unknown first-player color")
        // Two params per seat: a name is free text, so no separator inside one is safe.
        val names = colors.mapIndexedNotNull { index, color ->
            listOfNotNull(session.parms["name$index"], session.parms["name${index}b"])
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .takeIf { it.isNotEmpty() }
                ?.let { color to it }
        }.toMap()
        val statsDeviceId = if (session.parms["shareStats"] == "true") {
            if (session.headers["x-dune-panel"] != "1") return error("Start sharing from the panel")
            dubblegConnection.credential()?.deviceId ?: return error("Connect DubbleG before enabling stats sharing")
        } else null
        val result = startDuneSession(colors, first, nowMs, names)
        return applyDuneAction {
            if (result is DuneActionResult.Success) DuneActionResult.Success(result.session.copy(statsDeviceId = statsDeviceId)) else result
        }
    }

    private fun pauseDune(nowMs: Long): Response = withDuneLock {
        when (val result = toggleDunePause(Steward.dune.value, nowMs)) {
            is DuneActionResult.Error -> error(result.message)
            is DuneActionResult.Success -> {
                Steward.postDune(result.session)
                val cue = if (result.session.paused) "dune_timer_paused" else "dune_game_resumed"
                Steward.emitAudioClips(listOf(ClipSpec(cue, boostMillibels = 600)))
                json(duneJson(result.session))
            }
        }
    }

    // Swordmaster, Mentat and High Council all cue only on acquisition — releasing is a
    // correction, not a game event.
    private fun toggleFor(
        session: IHTTPSession,
        cuePrefix: String,
        acquiring: (DunePlayerColor, DuneSession) -> Boolean,
        action: (DuneSession, DunePlayerColor) -> DuneActionResult,
    ): Response {
        val player = DunePlayerColor.fromWireName(session.parms["color"]) ?: return error("unknown player color")
        return applyDuneAction(
            playCue = false,
            cues = { previous ->
                if (acquiring(player, previous)) listOf(ClipSpec("$cuePrefix${player.wireName}.wav", DUNE_CUE_BOOST))
                else emptyList()
            },
        ) { action(it, player) }
    }

    private fun recordCheckpoint(session: IHTTPSession, nowMs: Long): Response {
        val winner = DunePlayerColor.fromWireName(session.parms["winner"])
        return applyDuneAction { dune ->
            val points = dune.playerColors.mapNotNull { color ->
                session.parms["vp_${color.wireName}"]?.toIntOrNull()?.let { color to it }
            }.toMap()
            val final = session.parms["final"] == "true" || dune.round >= 10
            recordDuneScoreCheckpoint(dune, points, nowMs, final, winner)
        }
    }

    private fun applyDuneAction(
        playCue: Boolean = true,
        turnEndCue: String? = null,
        cues: (DuneSession) -> List<ClipSpec> = { emptyList() },
        action: (DuneSession) -> DuneActionResult,
    ): Response = withDuneLock {
        val previous = Steward.dune.value
        when (val result = action(previous)) {
            is DuneActionResult.Error -> error(result.message)
            is DuneActionResult.Success -> {
                applyDuneResult(result, playCue, previous, turnEndCue, cues(previous))
                json(duneJson(result.session))
            }
        }
    }

    private fun replayDuneCue(): Response {
        val dune = Steward.dune.value
        if (!dune.active) return error("no active game")
        val clips = when {
            dune.winner != null -> listOf(ClipSpec(duneWinnerCueFilename(dune.winner), DUNE_CUE_BOOST))
            dune.phase == DunePhase.TURNS -> duneConflictClips(dune)
            else -> listOfNotNull(
                dunePhaseCueFilename(dune.phase)?.let { ClipSpec(it, DUNE_CUE_BOOST) },
                duneCheckpointDueClip(dune),
            )
        }
        if (clips.isEmpty()) return error("no cue available")
        Steward.emitAudioClips(clips)
        return ok()
    }

    private fun gestureJson(): JSONObject {
        val g = Steward.gestures.value
        return JSONObject()
            .put("armed", g.armed)
            .put("flat", g.flat)
            .put("spinDegrees", g.spinDegrees.toDouble())
            .put("shakeJolts", g.shakeJolts)
            .put("spins", g.spins)
            .put("spinsRejected", g.spinsRejected)
            .put("shakes", g.shakes)
            .put("taps", g.taps)
            .put("glyphs", g.glyphs)
            .put("lastGlyph", g.lastGlyph)
            .put("gravity", JSONArray(listOf(g.gravity.first, g.gravity.second, g.gravity.third).map { it.toDouble() }))
            .put("sensors", JSONArray(g.sensors))
            .put("clipsInstalled", clipsDir.list()?.size ?: 0)
    }

    private fun duneJson(dune: DuneSession): JSONObject = JSONObject()
        .put("testMode", false)
        .put("shareStats", dune.statsDeviceId != null)
        .put("active", dune.active)
        .put("round", dune.round)
        .put("conflict", dune.conflictTier.number)
        .put("phase", dune.phase.wireName)
        .put("phaseName", dune.phase.displayName)
        .put("first", dune.currentFirstPlayer?.wireName ?: "")
        .put("currentPlayer", dune.currentPlayer?.wireName ?: "")
        .put("playerNames", JSONObject().apply {
            dune.playerColors.forEach { color -> dune.seatName(color)?.let { put(color.wireName, it) } }
        })
        .put("colors", JSONArray(dune.playerColors.map { it.wireName }))
        .put("revealedPlayers", JSONArray(dune.revealedPlayers.map { it.wireName }))
        .put("thirdAgentPlayers", JSONArray(dune.thirdAgentPlayers.map { it.wireName }))
        .put("mentatHolder", dune.mentatHolder?.wireName.orEmpty())
        .put("turnOrder", JSONArray(dune.playerColors.map { it.wireName }))
        .put("highCouncilPlayers", JSONArray(dune.highCouncilPlayers.map { it.wireName }))
        .put("checkpointRequired", dune.checkpointRequired)
        .put("scoreCheckpoints", JSONArray(dune.scoreCheckpoints.map { checkpoint -> JSONObject()
            .put("conflict", checkpoint.conflict)
            .put("capturedAtMs", checkpoint.capturedAtMs)
            .put("victoryPoints", JSONObject().apply { checkpoint.victoryPoints.forEach { (color, points) -> put(color.wireName, points) } })
            .put("final", checkpoint.final)
            .put("winner", checkpoint.winner?.wireName ?: "")
        }))
        .put("agentTurnsRemaining", JSONObject().apply {
            dune.agentTurnsRemaining.forEach { (color, count) -> put(color.wireName, count) }
        })
        .put("turnsComplete", dune.turnsComplete)
        .put("turnStartedAtMs", dune.turnStartedAtMs)
        .put("paused", dune.paused)
        .put("pausedAtMs", dune.pausedAtMs ?: 0L)
        .put("phaseStartedAtMs", dune.phaseStartedAtMs)
        .put("winner", dune.winner?.wireName ?: "")
        .put("undoAvailable", dune.undoAvailable)
        .put("turnUndoPlayer", dune.turnUndo?.player?.wireName.orEmpty())
        .put("roundStartedAtMs", dune.roundStartedAtMs)
        .put("gameElapsedMs", duneGameElapsedMs(dune, System.currentTimeMillis()))
        // Remaining rather than a due time: the panel's clock is another device's and may be off.
        .put("autoAdvance", duneAutoAdvance(dune)?.let { auto ->
            JSONObject()
                .put("next", auto.next)
                .put("durationMs", auto.durationMs)
                .put("remainingMs", (auto.dueAtMs - System.currentTimeMillis()).coerceAtLeast(0L))
        } ?: JSONObject.NULL)

    // The whole history as a file download: the phone keeps the only copy, and uninstalling
    // the app deletes it.
    private fun exportHistory(nowMs: Long): Response {
        val body = JSONObject()
            .put("exported_at_ms", nowMs)
            .put("games", JSONArray(historyStore.records()))
            .toString(2)
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(nowMs))
        return newFixedLengthResponse(Response.Status.OK, "application/json", body).apply {
            addHeader("Content-Disposition", "attachment; filename=\"dune-companion-history-$day.json\"")
        }
    }

    private fun json(body: JSONObject): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", body.toString())

    private fun ok(): Response = json(JSONObject().put("ok", true))

    private fun error(message: String): Response =
        newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", JSONObject().put("error", message).toString())
}
