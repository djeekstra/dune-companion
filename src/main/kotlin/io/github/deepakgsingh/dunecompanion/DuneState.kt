package io.github.deepakgsingh.dunecompanion

import kotlin.random.Random

enum class DunePlayerColor(val wireName: String, val displayName: String) {
    BLUE("blue", "BLUE"),
    GREEN("green", "GREEN"),
    RED("red", "RED"),
    YELLOW("yellow", "YELLOW");

    companion object {
        fun fromWireName(value: String?): DunePlayerColor? =
            entries.firstOrNull { it.wireName == value?.lowercase() }
    }
}

enum class ConflictTier(val number: Int, val roman: String) {
    I(1, "I"), II(2, "II"), III(3, "III"),
}

enum class DunePhase(val wireName: String, val displayName: String) {
    TURNS("turns", "PLAYER TURNS"),
    COMBAT("combat", "COMBAT"),
    COMBAT_COMPLETE("combat-complete", "COLLECT YOUR REWARDS"),
    MAKERS("makers", "MAKERS"),
    RECALL("recall", "RECALL");

    companion object {
        fun fromWireName(value: String?): DunePhase? = entries.firstOrNull { it.wireName == value }
    }
}

data class DuneSession(
    val active: Boolean = false,
    val playerColors: List<DunePlayerColor> = emptyList(),
    // A seat, not a person: two players sharing one colour is a normal way to fill a table.
    val playerNames: Map<DunePlayerColor, List<String>> = emptyMap(),
    val startingFirstPlayer: DunePlayerColor? = null,
    val round: Int = 1,
    val phase: DunePhase = DunePhase.TURNS,
    val currentPlayer: DunePlayerColor? = null,
    val revealedPlayers: Set<DunePlayerColor> = emptySet(),
    // Swordmaster: permanent, per player, worth a third agent every round from here on.
    val thirdAgentPlayers: Set<DunePlayerColor> = emptySet(),
    // Mentat: lives on a board space, is taken by placing an agent there, and returns to
    // the board at the end of the round. So it is at most one holder per round, never
    // transferred mid-round, and never part of the round-reset agent count.
    val mentatHolder: DunePlayerColor? = null,
    val highCouncilPlayers: Set<DunePlayerColor> = emptySet(),
    val scoreCheckpoints: List<DuneScoreCheckpoint> = emptyList(),
    val agentTurnsRemaining: Map<DunePlayerColor, Int> = emptyMap(),
    val turnStartedAtMs: Long = 0L,
    // Active game time at the beginning of the current turn. Unlike wall-clock timestamps,
    // this remains stable across pause/resume and makes completed-turn duration unambiguous.
    val turnStartedGameElapsedMs: Long = 0L,
    val paused: Boolean = false,
    val pausedAtMs: Long? = null,
    val fiveMinuteWarningPlayed: Boolean = false,
    val sevenMinuteWarningPlayed: Boolean = false,
    val tenMinuteWarningPlayed: Boolean = false,
    val phaseStartedAtMs: Long = 0L,
    val gameStartedAtMs: Long = 0L,
    // Stable identity for the public live session. gameStartedAtMs is shifted when a pause
    // ends so elapsed time excludes the break; the public announcement must not see that as
    // a new game.
    val liveSessionStartedAtMs: Long = 0L,
    val hourlyGameCueCount: Int = 0,
    val roundStartedAtMs: Long = 0L,
    val previousRoundStartedAtMs: Long? = null,
    val undoAvailable: Boolean = false,
    val turnUndo: DuneTurnUndo? = null,
    val turnRecords: List<DuneTurnRecord> = emptyList(),
    val nextTurnRecordId: Long = 1L,
    val testMode: Boolean = false,
    val winner: DunePlayerColor? = null,
    val endedAtMs: Long? = null,
    val winnerEndsAtMs: Long? = null,
) {
    /**
     * Everyone on a seat, as one string — "Nina & Omar" — or null if the seat was left unnamed.
     *
     * Everything *written* names the whole seat; only the spoken cues pick one of them (see
     * [duneNameClip]). Reading both aloud would need a recorded conjunction and would double
     * the length of a cue whose whole job is to say whose turn it is, for information the
     * panel and the display are both already showing.
     */
    fun seatName(color: DunePlayerColor): String? =
        playerNames[color]?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.joinToString(" & ")

    val conflictTier: ConflictTier
        get() = when (round) {
            1 -> ConflictTier.I
            in 2..6 -> ConflictTier.II
            else -> ConflictTier.III
        }

    val currentFirstPlayer: DunePlayerColor?
        get() {
            val start = startingFirstPlayer ?: return null
            val startIndex = playerColors.indexOf(start)
            if (startIndex < 0 || playerColors.isEmpty()) return null
            return playerColors[(startIndex + round - 1) % playerColors.size]
        }

    val turnsComplete: Boolean
        get() = playerColors.isNotEmpty() && revealedPlayers.containsAll(playerColors)

    val showingWinner: Boolean get() = active && winner != null

    val checkpointRequired: Boolean
        get() = phase == DunePhase.COMBAT_COMPLETE && round in SCORE_CHECKPOINT_ROUNDS &&
            scoreCheckpoints.none { it.conflict == round }
}

/**
 * Enough to hand back the turn that just ended.
 *
 * Deliberately a record of what the turn end *did*, not a snapshot of what the session *was*.
 * A snapshot would be simpler to write and wrong to apply: the Swordmaster, Mentat and High
 * Council toggles all stay legal after a turn ends, so restoring absolute values would silently
 * revoke a token claimed in between. [agentsSpent] and [revealed] are therefore deltas to
 * reverse. The clock and warning-flag fields have no such competing writer and are absolutes.
 *
 * Only one deep. A turn end overwrites this rather than stacking, because the case it exists
 * for is the gesture that just misfired, and an undo history the table has to reason about is
 * worse than none.
 */
data class DuneTurnUndo(
    val player: DunePlayerColor,
    // 0 when the count was already floored at zero, so undo cannot invent an agent.
    val agentsSpent: Int,
    // Whether this turn end is what marked them revealed — a second reveal of an already
    // revealed player must not un-reveal them.
    val revealed: Boolean,
    val turnStartedAtMs: Long,
    val phaseStartedAtMs: Long,
    val fiveMinuteWarningPlayed: Boolean,
    val sevenMinuteWarningPlayed: Boolean,
    val tenMinuteWarningPlayed: Boolean,
    // The round-level undo flag, which every turn end clears. Undoing the turn has to give it
    // back or an undone first turn of a round strands the round undo.
    val roundUndoAvailable: Boolean,
    val kind: DuneTurnKind = DuneTurnKind.AGENT,
    val turnRecordId: Long? = null,
    val turnStartedGameElapsedMs: Long = 0L,
)

enum class DuneTurnKind(val wireName: String) {
    AGENT("agent"), REVEAL("reveal");

    companion object {
        fun fromWireName(value: String?): DuneTurnKind? = entries.firstOrNull { it.wireName == value }
    }
}

data class DuneTurnRecord(
    val id: Long,
    val round: Int,
    val seat: DunePlayerColor,
    val seatName: String,
    val kind: DuneTurnKind,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val durationMs: Long,
)

data class DuneScoreCheckpoint(
    val conflict: Int,
    val capturedAtMs: Long,
    val victoryPoints: Map<DunePlayerColor, Int>,
    val final: Boolean = false,
    val winner: DunePlayerColor? = null,
)

/** Active game time, excluding every interval spent paused. */
fun duneGameElapsedMs(session: DuneSession, nowMs: Long): Long {
    if (session.gameStartedAtMs <= 0L) return 0L
    val effectiveNow = session.pausedAtMs.takeIf { session.paused }
        ?: session.endedAtMs
        ?: nowMs
    return (effectiveNow - session.gameStartedAtMs).coerceAtLeast(0L)
}

fun duneHourlyCueFilename(hour: Int): String =
    if (hour in 1..5) "dune_game_hour_$hour.wav" else "dune_game_another_hour.wav"

sealed class DuneActionResult {
    data class Success(val session: DuneSession) : DuneActionResult()
    data class Error(val message: String) : DuneActionResult()
}

fun startDuneSession(
    colors: List<DunePlayerColor>,
    first: DunePlayerColor,
    nowMs: Long,
    playerNames: Map<DunePlayerColor, List<String>> = emptyMap(),
    testMode: Boolean = false,
): DuneActionResult {
    if (colors.size !in 3..4) return DuneActionResult.Error("choose three or four player colors")
    if (colors.distinct().size != colors.size) return DuneActionResult.Error("player colors must be unique")
    if (first !in colors) return DuneActionResult.Error("first player must be an active color")
    return DuneActionResult.Success(
        DuneSession(
            active = true,
            playerColors = colors,
            playerNames = playerNames
                .mapValues { (_, names) ->
                    names.map { it.trim().take(MAX_PLAYER_NAME_LENGTH) }
                        .filter { it.isNotEmpty() }
                        .distinct()
                }
                .filterKeys { it in colors }
                .filterValues { it.isNotEmpty() },
            startingFirstPlayer = first,
            round = 1,
            phase = DunePhase.TURNS,
            currentPlayer = first,
            agentTurnsRemaining = colors.associateWith { 2 },
            turnStartedAtMs = nowMs,
            turnStartedGameElapsedMs = 0L,
            phaseStartedAtMs = nowMs,
            gameStartedAtMs = nowMs,
            liveSessionStartedAtMs = nowMs,
            roundStartedAtMs = nowMs,
            testMode = testMode,
        )
    )
}

private fun nextUnrevealedPlayer(session: DuneSession, after: DunePlayerColor): DunePlayerColor? {
    if (session.playerColors.isEmpty()) return null
    val startIndex = session.playerColors.indexOf(after).takeIf { it >= 0 } ?: return null
    for (offset in 1..session.playerColors.size) {
        val candidate = session.playerColors[(startIndex + offset) % session.playerColors.size]
        if (candidate !in session.revealedPlayers) return candidate
    }
    return null
}

private fun withNextTurn(
    session: DuneSession,
    current: DunePlayerColor,
    nowMs: Long,
    undo: DuneTurnUndo,
): DuneSession {
    val completed = appendCompletedTurn(session, undo, nowMs)
    val elapsed = duneGameElapsedMs(completed, nowMs)
    val recordId = completed.turnRecords.lastOrNull()?.id
    return completed.copy(
        currentPlayer = nextUnrevealedPlayer(session, current),
        turnStartedAtMs = nowMs,
        turnStartedGameElapsedMs = elapsed,
        fiveMinuteWarningPlayed = false,
        sevenMinuteWarningPlayed = false,
        tenMinuteWarningPlayed = false,
        undoAvailable = false,
        turnUndo = undo.copy(turnRecordId = recordId),
    )
}

private fun appendCompletedTurn(
    session: DuneSession,
    undo: DuneTurnUndo,
    nowMs: Long,
): DuneSession {
    val duration = (duneGameElapsedMs(session, nowMs) - undo.turnStartedGameElapsedMs).coerceAtLeast(0L)
    val record = DuneTurnRecord(
        id = session.nextTurnRecordId,
        round = session.round,
        seat = undo.player,
        seatName = session.seatName(undo.player) ?: undo.player.displayName,
        kind = undo.kind,
        startedAtMs = undo.turnStartedAtMs,
        endedAtMs = nowMs,
        durationMs = duration,
    )
    return session.copy(
        turnRecords = session.turnRecords + record,
        nextTurnRecordId = session.nextTurnRecordId + 1,
    )
}

/** Built from the session as it stood *before* the turn end, so it can reverse it. */
private fun turnUndoFor(
    session: DuneSession,
    player: DunePlayerColor,
    agentsSpent: Int,
    revealed: Boolean,
    kind: DuneTurnKind,
): DuneTurnUndo = DuneTurnUndo(
    player = player,
    agentsSpent = agentsSpent,
    revealed = revealed,
    turnStartedAtMs = session.turnStartedAtMs,
    phaseStartedAtMs = session.phaseStartedAtMs,
    fiveMinuteWarningPlayed = session.fiveMinuteWarningPlayed,
    sevenMinuteWarningPlayed = session.sevenMinuteWarningPlayed,
    tenMinuteWarningPlayed = session.tenMinuteWarningPlayed,
    roundUndoAvailable = session.undoAvailable,
    kind = kind,
    turnStartedGameElapsedMs = session.turnStartedGameElapsedMs,
)

fun endDuneAgentTurn(session: DuneSession, nowMs: Long): DuneActionResult {
    if (!session.active || session.showingWinner || session.phase != DunePhase.TURNS) {
        return DuneActionResult.Error("agent turns are not active")
    }
    if (session.paused) return DuneActionResult.Error("game is paused")
    val current = session.currentPlayer ?: return DuneActionResult.Error("all player turns are complete")
    // Agent turns are not capped. Too many things hand out extra placements for a running
    // count to be a rule the table can trust, and being told "you have none left" when the
    // board plainly says otherwise is worse than not counting. The count still tracks the
    // standard allocation for the card, it just no longer gates anything, so it floors at
    // zero rather than going negative.
    val remaining = session.agentTurnsRemaining[current] ?: 0
    val spent = if (remaining > 0) 1 else 0
    val updated = session.copy(
        agentTurnsRemaining = session.agentTurnsRemaining + (current to remaining - spent),
    )
    val undo = turnUndoFor(session, current, agentsSpent = spent, revealed = false, kind = DuneTurnKind.AGENT)
    return DuneActionResult.Success(withNextTurn(updated, current, nowMs, undo))
}

// Agents a player starts a round with. The Mentat is deliberately absent: it is claimed
// mid-round by spending an agent on its board space, so it only ever adjusts the running
// count, never the reset value.
fun duneAgentsPerRound(session: DuneSession, player: DunePlayerColor): Int =
    2 + if (player in session.thirdAgentPlayers) 1 else 0

private fun withAgentDelta(
    session: DuneSession,
    player: DunePlayerColor,
    delta: Int,
): Map<DunePlayerColor, Int> =
    session.agentTurnsRemaining +
        (player to ((session.agentTurnsRemaining[player] ?: 0) + delta).coerceAtLeast(0))

// Toggle, not add: a mis-tap used to be permanent and quietly skewed the agent count for
// the rest of the game.
fun toggleDuneThirdAgent(session: DuneSession, player: DunePlayerColor): DuneActionResult {
    if (!session.active || session.showingWinner) return DuneActionResult.Error("no active game")
    if (session.paused) return DuneActionResult.Error("game is paused")
    if (player !in session.playerColors) return DuneActionResult.Error("player must be active")
    val had = player in session.thirdAgentPlayers
    return DuneActionResult.Success(
        session.copy(
            thirdAgentPlayers = if (had) session.thirdAgentPlayers - player
                else session.thirdAgentPlayers + player,
            agentTurnsRemaining = withAgentDelta(session, player, if (had) -1 else 1),
        )
    )
}

// The Mentat is off the board once taken, so only one player can hold it in a round.
// Toggling the holder releases it; the host corrects a wrong pick by releasing first.
fun toggleDuneMentat(session: DuneSession, player: DunePlayerColor): DuneActionResult {
    if (!session.active || session.showingWinner) return DuneActionResult.Error("no active game")
    if (session.paused) return DuneActionResult.Error("game is paused")
    if (player !in session.playerColors) return DuneActionResult.Error("player must be active")
    val holder = session.mentatHolder
    if (holder == player) {
        return DuneActionResult.Success(
            session.copy(mentatHolder = null, agentTurnsRemaining = withAgentDelta(session, player, -1))
        )
    }
    if (holder != null) {
        val name = session.seatName(holder) ?: holder.displayName
        return DuneActionResult.Error("the mentat is already held by $name this round")
    }
    return DuneActionResult.Success(
        session.copy(mentatHolder = player, agentTurnsRemaining = withAgentDelta(session, player, 1))
    )
}

fun toggleDuneHighCouncil(session: DuneSession, player: DunePlayerColor): DuneActionResult {
    if (!session.active || session.showingWinner) return DuneActionResult.Error("no active game")
    if (player !in session.playerColors) return DuneActionResult.Error("player must be active")
    val updated = if (player in session.highCouncilPlayers) {
        session.highCouncilPlayers - player
    } else {
        session.highCouncilPlayers + player
    }
    return DuneActionResult.Success(session.copy(highCouncilPlayers = updated))
}

/**
 * A letter drawn on the display: the player whose turn it is has just sent an agent to that
 * space.
 *
 * Only ever grants. The panel's buttons toggle, which is right for correcting a mis-tap, but a
 * letter drawn twice — or one drawn and then misread as the same one — must not quietly take
 * the space back. Taking it back stays a panel job.
 */
fun claimDuneSpace(session: DuneSession, glyph: Glyph): DuneActionResult {
    if (!session.active || session.showingWinner) return DuneActionResult.Error("no active game")
    if (session.paused) return DuneActionResult.Error("game is paused")
    val player = session.currentPlayer
    if (session.phase != DunePhase.TURNS || player == null) return DuneActionResult.Error("no one has the turn")
    return when (glyph) {
        Glyph.SWORDMASTER ->
            if (player in session.thirdAgentPlayers) DuneActionResult.Error("already has the swordmaster")
            else toggleDuneThirdAgent(session, player)
        Glyph.MENTAT ->
            if (session.mentatHolder == player) DuneActionResult.Error("already has the mentat")
            else toggleDuneMentat(session, player)
        Glyph.HIGH_COUNCIL ->
            if (player in session.highCouncilPlayers) DuneActionResult.Error("already on the high council")
            else toggleDuneHighCouncil(session, player)
    }
}

fun recordDuneScoreCheckpoint(
    session: DuneSession,
    victoryPoints: Map<DunePlayerColor, Int>,
    nowMs: Long,
    final: Boolean = false,
    winner: DunePlayerColor? = null,
): DuneActionResult {
    if (!session.active || session.showingWinner) return DuneActionResult.Error("no active game")
    if (victoryPoints.keys != session.playerColors.toSet()) return DuneActionResult.Error("enter victory points for every player")
    if (victoryPoints.values.any { it !in 0..20 }) return DuneActionResult.Error("victory points must be between 0 and 20")
    if (session.scoreCheckpoints.any { it.conflict == session.round }) return DuneActionResult.Error("this checkpoint is already recorded")
    val isFinal = final || session.round >= 10
    if (!isFinal && session.round !in SCORE_CHECKPOINT_ROUNDS) return DuneActionResult.Error("scores are not due this round")
    if (isFinal && winner !in session.playerColors) return DuneActionResult.Error("choose the winner")
    val checkpoint = DuneScoreCheckpoint(session.round, nowMs, victoryPoints, isFinal, winner)
    return DuneActionResult.Success(
        session.copy(
            scoreCheckpoints = session.scoreCheckpoints + checkpoint,
            winner = winner.takeIf { isFinal },
            endedAtMs = nowMs.takeIf { isFinal },
            winnerEndsAtMs = (nowMs + Config.DUNE_WINNER_TTL_MS).takeIf { isFinal },
            undoAvailable = false.takeIf { isFinal } ?: session.undoAvailable,
        )
    )
}

fun finishDuneRevealTurn(session: DuneSession, nowMs: Long): DuneActionResult {
    if (!session.active || session.showingWinner || session.phase != DunePhase.TURNS) {
        return DuneActionResult.Error("reveal turns are not active")
    }
    if (session.paused) return DuneActionResult.Error("game is paused")
    val current = session.currentPlayer ?: return DuneActionResult.Error("all player turns are complete")
    val undo = turnUndoFor(
        session,
        current,
        agentsSpent = 0,
        revealed = current !in session.revealedPlayers,
        kind = DuneTurnKind.REVEAL,
    )
    val revealed = session.revealedPlayers + current
    val updated = session.copy(revealedPlayers = revealed)
    if (updated.turnsComplete) {
        val completed = appendCompletedTurn(updated, undo, nowMs)
        return DuneActionResult.Success(
            completed.copy(
                phase = DunePhase.COMBAT,
                currentPlayer = null,
                turnStartedAtMs = 0L,
                turnStartedGameElapsedMs = 0L,
                fiveMinuteWarningPlayed = false,
                sevenMinuteWarningPlayed = false,
                tenMinuteWarningPlayed = false,
                phaseStartedAtMs = nowMs,
                undoAvailable = false,
                // Kept across the move into COMBAT on purpose: the reveal that completes the
                // round is exactly the one a misfired shake is most likely to have faked.
                turnUndo = undo.copy(turnRecordId = completed.turnRecords.lastOrNull()?.id),
            )
        )
    }
    return DuneActionResult.Success(withNextTurn(updated, current, nowMs, undo))
}

/**
 * Hands the last ended turn back to the player who had it.
 *
 * There was no undo on any input path when the only way to end a turn was to tap the panel.
 * Gestures made turn ends cheap to trigger by accident — a knock, a phone set down with a
 * twist, a shake that was really a tilt — and a phantom turn end is otherwise
 * unrecoverable: the round moves on and the table is left arguing about whose turn it was.
 *
 * One level deep, and only back through a turn end. Undoing a whole round is [undoDuneRound].
 */
fun undoDuneTurn(session: DuneSession): DuneActionResult {
    if (!session.active || session.showingWinner) return DuneActionResult.Error("no active game")
    val undo = session.turnUndo ?: return DuneActionResult.Error("no turn to undo")
    // Second guard, independent of every site that clears the record: TURNS and COMBAT are the
    // only two phases a turn end can leave behind, so anything else means the round has moved
    // on and the record is stale. Cheaper than trusting six call sites to stay correct.
    if (session.phase != DunePhase.TURNS && session.phase != DunePhase.COMBAT) {
        return DuneActionResult.Error("the round has already moved on")
    }
    return DuneActionResult.Success(
        session.copy(
            // Always TURNS: both turn-end functions refuse to run in any other phase, so that
            // is provably where this turn began.
            phase = DunePhase.TURNS,
            currentPlayer = undo.player,
            revealedPlayers = if (undo.revealed) session.revealedPlayers - undo.player
                else session.revealedPlayers,
            agentTurnsRemaining = session.agentTurnsRemaining +
                (undo.player to (session.agentTurnsRemaining[undo.player] ?: 0) + undo.agentsSpent),
            turnStartedAtMs = undo.turnStartedAtMs,
            turnStartedGameElapsedMs = undo.turnStartedGameElapsedMs,
            phaseStartedAtMs = undo.phaseStartedAtMs,
            fiveMinuteWarningPlayed = undo.fiveMinuteWarningPlayed,
            sevenMinuteWarningPlayed = undo.sevenMinuteWarningPlayed,
            tenMinuteWarningPlayed = undo.tenMinuteWarningPlayed,
            undoAvailable = undo.roundUndoAvailable,
            turnUndo = null,
            turnRecords = undo.turnRecordId?.let { id -> session.turnRecords.filterNot { it.id == id } }
                ?: session.turnRecords,
            nextTurnRecordId = undo.turnRecordId
                ?.takeIf { it == session.nextTurnRecordId - 1L }
                ?.let { session.nextTurnRecordId - 1L }
                ?: session.nextTurnRecordId,
        )
    )
}

fun advanceDunePhase(session: DuneSession, nowMs: Long): DuneActionResult {
    if (!session.active || session.showingWinner) return DuneActionResult.Error("no active round")
    if (session.paused) return DuneActionResult.Error("game is paused")
    return when (session.phase) {
        DunePhase.TURNS -> {
            if (!session.turnsComplete) return DuneActionResult.Error("all players must finish their reveal turns")
            DuneActionResult.Success(session.copy(phase = DunePhase.COMBAT, phaseStartedAtMs = nowMs))
        }
        DunePhase.COMBAT -> DuneActionResult.Success(
            session.copy(phase = DunePhase.COMBAT_COMPLETE, phaseStartedAtMs = nowMs)
        )
        DunePhase.COMBAT_COMPLETE -> {
            if (session.checkpointRequired) return DuneActionResult.Error("record victory points before continuing")
            if (nowMs - session.phaseStartedAtMs < Config.DUNE_COMBAT_TO_MAKERS_DELAY_MS) {
                DuneActionResult.Error("makers phase is not ready yet")
            } else {
                DuneActionResult.Success(session.copy(phase = DunePhase.MAKERS, phaseStartedAtMs = nowMs))
            }
        }
        DunePhase.MAKERS -> DuneActionResult.Success(session.copy(phase = DunePhase.RECALL, phaseStartedAtMs = nowMs))
        DunePhase.RECALL -> advanceDuneSession(session, nowMs)
    }
}

fun advanceDuneSession(session: DuneSession, nowMs: Long): DuneActionResult {
    if (!session.active || session.showingWinner) return DuneActionResult.Error("no active round")
    if (session.paused) return DuneActionResult.Error("game is paused")
    if (session.phase != DunePhase.RECALL) return DuneActionResult.Error("finish the recall phase first")
    if (session.round >= 10) return DuneActionResult.Error("round ten is the final round")
    val nextRound = session.round + 1
    val first = session.copy(round = nextRound).currentFirstPlayer
    return DuneActionResult.Success(
        session.copy(
            round = nextRound,
            phase = DunePhase.TURNS,
            currentPlayer = first,
            revealedPlayers = emptySet(),
            // The Mentat goes back to its board space at the end of every round.
            mentatHolder = null,
            agentTurnsRemaining = session.playerColors.associateWith { duneAgentsPerRound(session, it) },
            turnStartedAtMs = nowMs,
            turnStartedGameElapsedMs = duneGameElapsedMs(session, nowMs),
            paused = false,
            pausedAtMs = null,
            fiveMinuteWarningPlayed = false,
            sevenMinuteWarningPlayed = false,
            tenMinuteWarningPlayed = false,
            phaseStartedAtMs = nowMs,
            previousRoundStartedAtMs = session.roundStartedAtMs,
            roundStartedAtMs = nowMs,
            undoAvailable = true,
            // The new round re-enters TURNS, which is a phase undoDuneTurn accepts, so this is
            // the one clear that the phase guard cannot cover.
            turnUndo = null,
        )
    )
}

fun undoDuneRound(session: DuneSession): DuneActionResult {
    if (!session.active || session.showingWinner) return DuneActionResult.Error("no active round")
    if (!session.undoAvailable || session.round <= 1) return DuneActionResult.Error("nothing to undo")
    val priorRound = session.round - 1
    return DuneActionResult.Success(
        session.copy(
            round = priorRound,
            phase = DunePhase.RECALL,
            currentPlayer = null,
            revealedPlayers = session.playerColors.toSet(),
            turnStartedAtMs = 0L,
            turnStartedGameElapsedMs = 0L,
            fiveMinuteWarningPlayed = false,
            sevenMinuteWarningPlayed = false,
            tenMinuteWarningPlayed = false,
            phaseStartedAtMs = session.previousRoundStartedAtMs ?: session.roundStartedAtMs,
            roundStartedAtMs = session.previousRoundStartedAtMs ?: session.roundStartedAtMs,
            previousRoundStartedAtMs = null,
            undoAvailable = false,
            turnUndo = null,
        )
    )
}

fun endDuneRound(session: DuneSession, nowMs: Long): DuneActionResult {
    if (!session.active || session.showingWinner) return DuneActionResult.Error("no active round")
    if (session.round >= 10) return DuneActionResult.Error("round ten is the final round")
    return advanceDuneSession(
        session.copy(
            phase = DunePhase.RECALL,
            paused = false,
            pausedAtMs = null,
        ),
        nowMs,
    )
}

fun toggleDunePause(session: DuneSession, nowMs: Long): DuneActionResult {
    if (!session.active || session.showingWinner) return DuneActionResult.Error("no active game")
    if (session.phase != DunePhase.TURNS || session.currentPlayer == null) {
        return DuneActionResult.Error("the player timer is not active")
    }
    if (!session.paused) {
        return DuneActionResult.Success(session.copy(paused = true, pausedAtMs = nowMs))
    }
    val pausedAt = session.pausedAtMs ?: nowMs
    val pauseDuration = (nowMs - pausedAt).coerceAtLeast(0L)
    fun shift(timestamp: Long): Long = if (timestamp > 0L) timestamp + pauseDuration else timestamp
    return DuneActionResult.Success(
        session.copy(
            paused = false,
            pausedAtMs = null,
            turnStartedAtMs = shift(session.turnStartedAtMs),
            phaseStartedAtMs = shift(session.phaseStartedAtMs),
            gameStartedAtMs = shift(session.gameStartedAtMs),
            roundStartedAtMs = shift(session.roundStartedAtMs),
            previousRoundStartedAtMs = session.previousRoundStartedAtMs?.let(::shift),
        )
    )
}

fun markDuneTimerWarning(
    session: DuneSession,
    fiveMinutes: Boolean = false,
    sevenMinutes: Boolean = false,
    tenMinutes: Boolean = false,
): DuneSession =
    session.copy(
        fiveMinuteWarningPlayed = session.fiveMinuteWarningPlayed || fiveMinutes,
        sevenMinuteWarningPlayed = session.sevenMinuteWarningPlayed || sevenMinutes,
        tenMinuteWarningPlayed = session.tenMinuteWarningPlayed || tenMinutes,
    )

fun endDuneSession(
    session: DuneSession,
    winner: DunePlayerColor,
    nowMs: Long,
    winnerDurationMs: Long,
): DuneActionResult {
    if (!session.active || session.showingWinner) return DuneActionResult.Error("no active game")
    if (winner !in session.playerColors) return DuneActionResult.Error("winner must be an active color")
    return DuneActionResult.Success(
        session.copy(
            winner = winner,
            endedAtMs = nowMs,
            winnerEndsAtMs = nowMs + winnerDurationMs,
            undoAvailable = false,
        )
    )
}

// Players the rotation passed over because they had already revealed. Derived by diffing two
// sessions, like duneTransitionCueFilenames, so the cue logic stays out of the pure state
// functions. Without this the turn simply jumps over someone and the table is left guessing
// whose turn it is and why.
fun duneSkippedRevealedPlayers(previous: DuneSession, next: DuneSession): List<DunePlayerColor> {
    val from = previous.currentPlayer ?: return emptyList()
    // The reveal that completes a round leaves no current player, and "who did we step over on
    // the way to nobody" has no answer. Without this the loop below finds no one to stop at and
    // walks the whole table, naming every player who had revealed — including the one who just
    // did. Measured at the table on 2026-07-31: the last reveal of a round announced the player
    // who had made it, as having already made it.
    if (next.currentPlayer == null) return emptyList()
    val order = next.playerColors
    val startIndex = order.indexOf(from).takeIf { it >= 0 } ?: return emptyList()
    val skipped = mutableListOf<DunePlayerColor>()
    for (offset in 1..order.size) {
        val candidate = order[(startIndex + offset) % order.size]
        if (candidate == next.currentPlayer) break
        if (candidate in next.revealedPlayers) skipped += candidate
    }
    return skipped
}

/**
 * Just the player's name, falling back to their colour.
 *
 * Recorded alone rather than inside each sentence that uses it, so a regular costs exactly
 * one clip no matter how many things the companion learns to say about them. The price is a
 * splice at a word boundary rather than a sentence boundary, which is audible.
 *
 * **When two people share a seat, one of them is picked at random, per cue.** Saying both
 * would need a recorded "and" and would stretch every cue that names anyone; saying the first
 * one always would make the second player's name furniture. Rerolling per cue rather than once
 * per game is deliberate — it is the same two people either way, so the only thing a fixed
 * choice buys is that the table stops hearing one of them.
 *
 * The name not picked stays ahead of the colour in [ClipSpec.alternatives], so a seat where
 * only one of the two has been recorded still speaks a name rather than dropping to "RED".
 */
fun duneNameClip(
    session: DuneSession,
    color: DunePlayerColor,
    random: Random = Random.Default,
): ClipSpec {
    // A person first, then one of the names that person is recorded under. Flattening both
    // choices into one draw would quietly favour whoever has an alias.
    // Filtered once, so `typed` and `people` stay index-aligned: the name spoken when no clip
    // is installed is whatever the host typed for the person the draw picked.
    val typed = session.playerNames[color].orEmpty().filter { duneNameSlugs(it).isNotEmpty() }
    val people = typed.map(::duneNameSlugs)
    val chosen = people.indices.randomOrNull(random)
    val person = chosen?.let { people[it] }.orEmpty()
    val spoken = person.randomOrNull(random)
    val fallbacks = person.filterNot { it == spoken } +
        people.filterIndexed { index, _ -> index != chosen }.flatten() +
        color.wireName
    return ClipSpec(
        name = "dune_name_${spoken ?: color.wireName}.wav",
        boostMillibels = DUNE_CUE_BOOST,
        alternatives = fallbacks.distinct().map { "dune_name_$it.wav" },
        speech = chosen?.let { typed[it] } ?: color.displayName.lowercase(),
    )
}

/** "Nina" + "has already revealed." */
fun duneAlreadyRevealedClips(session: DuneSession, color: DunePlayerColor): List<ClipSpec> =
    listOf(
        duneNameClip(session, color),
        ClipSpec(DUNE_PHRASE_ALREADY_REVEALED, DUNE_CUE_BOOST),
    )

/**
 * "Nina" + "has the turn." — the handover, said after the rotation stepped over someone.
 *
 * Follows the "has already revealed" announcements rather than replacing them: the skip
 * explains why the turn appeared to jump, and this says where it landed. Either half alone
 * leaves the table with half the question — why did it move, and who is up.
 */
fun duneNextTurnClips(session: DuneSession, color: DunePlayerColor): List<ClipSpec> =
    listOf(
        duneNameClip(session, color),
        ClipSpec(DUNE_PHRASE_TURN, DUNE_CUE_BOOST),
    )

/**
 * "Nina" + "still has the turn."
 *
 * An undo has to be audible. The person tapping it is not usually the person whose turn came
 * back, and a silent correction leaves the table on whoever the panel last announced.
 */
fun duneTurnRestoredClips(session: DuneSession, color: DunePlayerColor): List<ClipSpec> =
    listOf(
        duneNameClip(session, color),
        ClipSpec(DUNE_PHRASE_TURN_RESTORED, DUNE_CUE_BOOST),
    )

/** A turn-timer nag: the elapsed-time clip. */
fun duneTimerClips(elapsed: DuneTimerMark): List<ClipSpec> = buildList {
    add(ClipSpec(elapsed.clip, DUNE_CUE_BOOST))
}

enum class DuneTimerMark(val clip: String) {
    FIVE("dune_timer_five.wav"),
    SEVEN("dune_timer_seven.wav"),
    TEN("dune_timer_ten.wav"),
}

/**
 * The music laid under a phase announcement, or null for the phases that get none.
 *
 * Combat is the only beat of a Dune round that is genuinely an event rather than an
 * administrative step, so it is the one that earns a sting — one going in, one coming out.
 * Kept separate from [duneTransitionClips] because a bed is not part of the sequence: it
 * starts with the announcement and outlives it.
 */
fun duneTransitionBed(previous: DuneSession, next: DuneSession): ClipSpec? {
    if (previous.phase == next.phase || next.winner != null) return null
    return when (next.phase) {
        DunePhase.COMBAT -> ClipSpec(DUNE_BED_COMBAT, volume = Config.DUNE_BED_VOLUME)
        DunePhase.COMBAT_COMPLETE -> ClipSpec(DUNE_BED_REWARDS, volume = Config.DUNE_BED_VOLUME)
        else -> null
    }
}

// Extension-less: whichever format is installed wins, and a missing bed is a logged no-op, so
// the music can be pulled off the device without touching the app.
const val DUNE_BED_COMBAT = "dune_bed_combat"
const val DUNE_BED_REWARDS = "dune_bed_rewards"

const val DUNE_PHRASE_ALREADY_REVEALED = "dune_phrase_already_revealed.wav"
const val DUNE_PHRASE_TURN = "dune_phrase_turn.wav"
const val DUNE_PHRASE_FIRST_PLAYER = "dune_phrase_first_player.wav"
const val DUNE_PHRASE_TURN_RESTORED = "dune_phrase_turn_restored.wav"

// Player names are free text; clip files are not. Anything that isn't a letter or digit is
// dropped, so "Nina" and "nina " reach the same clip. Null when nothing usable is left.
fun duneClipSlug(name: String?): String? =
    name?.lowercase()?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() }

/** The clip slug that speaks this name, as a list. Empty when nothing is usable. */
fun duneNameSlugs(name: String?): List<String> {
    val slug = duneClipSlug(name) ?: return emptyList()
    return listOf(slug)
}

/**
 * The round-opening announcement, in two halves: which conflict this is, then who leads.
 *
 * Split deliberately. As one recording per round *and* per speaker it cost ten clips to add
 * a player; split it costs one, because only the second half mentions anyone. The price is a
 * seam mid-announcement, which is why the halves are whole sentences rather than clauses.
 */
fun duneConflictClips(session: DuneSession): List<ClipSpec> {
    val color = session.currentFirstPlayer ?: return emptyList()
    return listOf(
        ClipSpec("dune_conflict_%02d.wav".format(session.round), DUNE_CUE_BOOST),
        duneNameClip(session, color),
        ClipSpec(DUNE_PHRASE_FIRST_PLAYER, DUNE_CUE_BOOST),
    )
}

// Every Dune cue is played at the same lift over the phone's speaker.
const val DUNE_CUE_BOOST = 600

fun dunePhaseCueFilename(phase: DunePhase): String? = when (phase) {
    DunePhase.TURNS -> null
    DunePhase.COMBAT -> "dune_phase_combat.wav"
    DunePhase.COMBAT_COMPLETE -> "dune_collect_rewards.wav"
    DunePhase.MAKERS -> "dune_phase_makers.wav"
    DunePhase.RECALL -> "dune_phase_recall.wav"
}

fun duneTransitionClips(previous: DuneSession, next: DuneSession): List<ClipSpec> = buildList {
    val phaseChanged = previous.phase != next.phase
    val newSession = !previous.active && next.active || previous.gameStartedAtMs != next.gameStartedAtMs
    when {
        next.winner != null -> add(ClipSpec(duneWinnerCueFilename(next.winner), DUNE_CUE_BOOST))
        phaseChanged || newSession ->
            if (next.phase == DunePhase.TURNS) addAll(duneConflictClips(next))
            else dunePhaseCueFilename(next.phase)?.let { add(ClipSpec(it, DUNE_CUE_BOOST)) }
    }
}

private const val MAX_PLAYER_NAME_LENGTH = 32
private val SCORE_CHECKPOINT_ROUNDS = setOf(5, 9, 10)

fun duneWinnerCueFilename(color: DunePlayerColor): String = "dune_winner_${color.wireName}.wav"

// Short audible marker so the whole table hears a turn close, not just whoever is holding
// the panel. Extension-less on purpose: clipFile resolves whichever format is installed,
// and a missing clip is a logged no-op rather than a failure.
const val DUNE_TURN_END_CUE = "dune_turn_end"

// A reveal closes a player's whole round, an agent turn closes one placement, and by ear those
// had been the same event. Distinct sounds mean the table can hear which of the two just
// happened without reading the card — worth more here than anywhere else, because the two
// gestures that produce them (spin, shake) are easy to perform by accident.
// Level-matched to DUNE_TURN_END_CUE at -11.3 LUFS rather than to the spoken pack: these two
// are heard against each other, and the speech is a different kind of sound entirely.
const val DUNE_REVEAL_END_CUE = "dune_reveal_end"

// An agent turn the rules refuse — the game is paused, or not in player turns. It has to be
// audibly distinct from DUNE_TURN_END_CUE: silence would read as "the sensor missed me" and the
// turn-end cue would read as "your turn is over" when it isn't.
const val DUNE_TURN_REJECTED_CUE = "dune_turn_rejected"
