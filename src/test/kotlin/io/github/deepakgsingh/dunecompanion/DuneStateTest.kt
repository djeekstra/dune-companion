package io.github.deepakgsingh.dunecompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class DuneStateTest {
    private val colors = listOf(DunePlayerColor.BLUE, DunePlayerColor.RED, DunePlayerColor.YELLOW)

    @Test
    fun agentTurnsAreNotCapped() {
        // Too many things hand out extra placements for a running count to be a rule the
        // table can trust; being told "you have none left" while the board says otherwise is
        // worse than not counting at all.
        var session = successfulStart(colors, DunePlayerColor.RED)
        repeat(12) {
            val result = endDuneAgentTurn(session, 0L)
            assertTrue("agent turn $it was refused", result is DuneActionResult.Success)
            session = (result as DuneActionResult.Success).session
        }
        assertEquals(0, session.agentTurnsRemaining[DunePlayerColor.RED])
    }

    @Test
    fun completedTurnsRecordKindSeatAndPauseExcludedDuration() {
        var session = (startDuneSession(
            colors, DunePlayerColor.RED, nowMs = 100L,
            playerNames = mapOf(DunePlayerColor.RED to listOf("Maya", "Omar")),
        ) as DuneActionResult.Success).session
        session = (endDuneAgentTurn(session, 200L) as DuneActionResult.Success).session
        assertEquals(1, session.turnRecords.size)
        assertEquals(DuneTurnKind.AGENT, session.turnRecords.single().kind)
        assertEquals("Maya & Omar", session.turnRecords.single().seatName)
        assertEquals(100L, session.turnRecords.single().durationMs)

        session = (toggleDunePause(session, 300L) as DuneActionResult.Success).session
        session = (toggleDunePause(session, 500L) as DuneActionResult.Success).session
        session = (finishDuneRevealTurn(session, 600L) as DuneActionResult.Success).session
        assertEquals(DuneTurnKind.REVEAL, session.turnRecords.last().kind)
        assertEquals(200L, session.turnRecords.last().durationMs)
    }

    @Test
    fun undoRemovesTheCompletedTurnRecordAndRestoresTheRecordId() {
        val started = successfulStart(colors, DunePlayerColor.RED, nowMs = 100L)
        val ended = (endDuneAgentTurn(started, 200L) as DuneActionResult.Success).session
        assertEquals(2L, ended.nextTurnRecordId)
        val undone = (undoDuneTurn(ended) as DuneActionResult.Success).session
        assertTrue(undone.turnRecords.isEmpty())
        assertEquals(1L, undone.nextTurnRecordId)
        assertEquals(started, undone)
    }

    @Test
    fun theRotationReportsWhoItPassedOver() {
        val started = successfulStart(colors, DunePlayerColor.RED)   // order blue, red, yellow
        // Yellow reveals, so the rotation must step over them next time round.
        val afterReveal = (finishDuneRevealTurn(started, 0L) as DuneActionResult.Success).session
            .let { it.copy(currentPlayer = DunePlayerColor.YELLOW, revealedPlayers = setOf(DunePlayerColor.RED)) }
        val revealed = (finishDuneRevealTurn(afterReveal, 0L) as DuneActionResult.Success).session
        assertEquals(DunePlayerColor.BLUE, revealed.currentPlayer)

        // Blue ends a turn: red and yellow have both revealed, so both get named.
        val next = (endDuneAgentTurn(revealed, 0L) as DuneActionResult.Success).session
        assertEquals(DunePlayerColor.BLUE, next.currentPlayer)
        assertEquals(
            listOf(DunePlayerColor.RED, DunePlayerColor.YELLOW),
            duneSkippedRevealedPlayers(revealed, next),
        )
    }

    @Test
    fun theRevealThatCompletesARoundAnnouncesNobody() {
        // Found at the table: the last reveal of a round left no current player, the skip walk
        // had nothing to stop at, and every revealed seat got named — the one that had just
        // revealed included.
        var session = successfulStart(colors, DunePlayerColor.BLUE)
        repeat(colors.size - 1) {
            val next = (finishDuneRevealTurn(session, 0L) as DuneActionResult.Success).session
            session = next
        }
        val last = (finishDuneRevealTurn(session, 0L) as DuneActionResult.Success).session
        assertTrue(last.turnsComplete)
        assertNull(last.currentPlayer)
        assertEquals(emptyList<DunePlayerColor>(), duneSkippedRevealedPlayers(session, last))
    }

    @Test
    fun steppingOverARevealedPlayerNamesWhoseTurnItIsNow() {
        val session = successfulStart(colors, DunePlayerColor.RED)
            .copy(playerNames = mapOf(DunePlayerColor.BLUE to listOf("Nina")))
        assertEquals(
            listOf("dune_name_nina.wav", DUNE_PHRASE_TURN),
            duneNextTurnClips(session, DunePlayerColor.BLUE).map { it.name },
        )
    }

    @Test
    fun nobodyIsAnnouncedWhenTheRotationSkipsNobody() {
        val started = successfulStart(colors, DunePlayerColor.RED)
        val next = (endDuneAgentTurn(started, 0L) as DuneActionResult.Success).session
        assertEquals(emptyList<DunePlayerColor>(), duneSkippedRevealedPlayers(started, next))
    }

    @Test
    fun revealedClipPrefersTheNameAndFallsBackToTheColour() {
        val session = successfulStart(colors, DunePlayerColor.RED)
            .copy(playerNames = mapOf(DunePlayerColor.RED to listOf("Nina")))
        val red = duneAlreadyRevealedClips(session, DunePlayerColor.RED)
        assertEquals(
            listOf("dune_name_nina.wav", "dune_name_red.wav"),
            red[0].candidates,
        )
        // The sentence half is shared by every player, so it never varies.
        assertEquals(DUNE_PHRASE_ALREADY_REVEALED, red[1].name)
        // No name recorded: the colour clip is the only candidate worth trying.
        assertEquals(
            listOf("dune_name_blue.wav"),
            duneAlreadyRevealedClips(session, DunePlayerColor.BLUE)[0].candidates,
        )
    }

    @Test
    fun aSharedSeatSpeaksOneNameAtRandomAndKeepsTheOtherAsFallback() {
        val session = successfulStart(colors, DunePlayerColor.RED)
            .copy(playerNames = mapOf(DunePlayerColor.RED to listOf("Nina", "Omar")))
        // Both names are written everywhere, so the panel and the display show the whole seat.
        assertEquals("Nina & Omar", session.seatName(DunePlayerColor.RED))

        // Whichever name is spoken, the other one is tried before the colour — a seat where
        // only one of the two has been recorded still says a name.
        // Seeded, so "both names come up" is asserted rather than hoped for.
        val random = Random(7)
        val spoken = (1..40).map { duneNameClip(session, DunePlayerColor.RED, random).candidates }.toSet()
        assertEquals(
            setOf(
                listOf("dune_name_nina.wav", "dune_name_omar.wav", "dune_name_red.wav"),
                listOf("dune_name_omar.wav", "dune_name_nina.wav", "dune_name_red.wav"),
            ),
            spoken,
        )
    }

    @Test
    fun theConflictAnnouncementPrefersTheFirstPlayersName() {
        val session = successfulStart(colors, DunePlayerColor.RED)
            .copy(playerNames = mapOf(DunePlayerColor.RED to listOf("Nina")))
        assertEquals(DunePlayerColor.RED, session.currentFirstPlayer)
        val clips = duneConflictClips(session)
        // Which conflict this is never mentions anyone, so it is one clip per round forever;
        // only the middle clip varies by speaker.
        assertEquals("dune_conflict_01.wav", clips[0].name)
        assertEquals(listOf("dune_name_nina.wav", "dune_name_red.wav"), clips[1].candidates)
        assertEquals(DUNE_PHRASE_FIRST_PLAYER, clips[2].name)
        // Round two hands first player to yellow, who has no name recorded.
        val unnamed = duneConflictClips(session.copy(round = 2))
        assertEquals("dune_conflict_02.wav", unnamed[0].name)
        assertEquals(listOf("dune_name_yellow.wav"), unnamed[1].candidates)
    }

    @Test
    fun clipSlugsSurviveRealWorldNames() {
        assertEquals("nina", duneClipSlug("Nina"))
        assertEquals("nina", duneClipSlug("  nina "))
        assertEquals("maryjane", duneClipSlug("Mary-Jane"))
        assertNull(duneClipSlug(""))
        assertNull(duneClipSlug("!!!"))
        assertNull(duneClipSlug(null))
    }

    @Test
    fun conflictTiersMatchTheOriginalDeck() {
        assertEquals(ConflictTier.I, DuneSession(round = 1).conflictTier)
        for (round in 2..6) assertEquals(ConflictTier.II, DuneSession(round = round).conflictTier)
        for (round in 7..10) assertEquals(ConflictTier.III, DuneSession(round = round).conflictTier)
    }

    @Test
    fun firstPlayerRotatesInClockwiseSeatOrder() {
        val base = successfulStart(colors, DunePlayerColor.RED)
        val expected = listOf(
            DunePlayerColor.RED,
            DunePlayerColor.YELLOW,
            DunePlayerColor.BLUE,
            DunePlayerColor.RED,
        )
        expected.forEachIndexed { index, color ->
            assertEquals(color, base.copy(round = index + 1).currentFirstPlayer)
        }
    }

    @Test
    fun startRejectsInvalidSeating() {
        assertTrue(startDuneSession(colors.take(2), DunePlayerColor.BLUE, 1L) is DuneActionResult.Error)
        assertTrue(
            startDuneSession(
                listOf(DunePlayerColor.BLUE, DunePlayerColor.BLUE, DunePlayerColor.RED),
                DunePlayerColor.BLUE,
                1L,
            ) is DuneActionResult.Error
        )
        assertTrue(startDuneSession(colors, DunePlayerColor.GREEN, 1L) is DuneActionResult.Error)
    }

    @Test
    fun advanceAndUndoRestoreThePriorRoundClock() {
        val started = successfulStart(colors, DunePlayerColor.BLUE, nowMs = 1_000L)
        val advanced = (advanceDuneSession(started.copy(phase = DunePhase.RECALL), 5_000L) as DuneActionResult.Success).session
        assertEquals(2, advanced.round)
        assertEquals(5_000L, advanced.roundStartedAtMs)
        assertEquals(DunePlayerColor.RED, advanced.currentFirstPlayer)
        assertEquals(DunePlayerColor.RED, advanced.currentPlayer)
        assertEquals(2, advanced.agentTurnsRemaining[DunePlayerColor.RED])

        val undone = (undoDuneRound(advanced) as DuneActionResult.Success).session
        assertEquals(1, undone.round)
        assertEquals(1_000L, undone.roundStartedAtMs)
        assertFalse(undone.undoAvailable)
        assertTrue(undoDuneRound(undone) is DuneActionResult.Error)
    }

    @Test
    fun roundTenCannotAdvance() {
        val atTen = successfulStart(colors, DunePlayerColor.BLUE).copy(round = 10, phase = DunePhase.RECALL)
        assertTrue(advanceDuneSession(atTen, 2_000L) is DuneActionResult.Error)
    }

    @Test
    fun agentAndRevealTurnsRotateUntilEveryoneReveals() {
        var session = successfulStart(colors, DunePlayerColor.BLUE, nowMs = 100L)
        session = (endDuneAgentTurn(session, 200L) as DuneActionResult.Success).session
        assertEquals(1, session.agentTurnsRemaining[DunePlayerColor.BLUE])
        assertEquals(DunePlayerColor.RED, session.currentPlayer)

        session = (finishDuneRevealTurn(session, 300L) as DuneActionResult.Success).session
        assertTrue(DunePlayerColor.RED in session.revealedPlayers)
        assertEquals(DunePlayerColor.YELLOW, session.currentPlayer)

        session = (finishDuneRevealTurn(session, 400L) as DuneActionResult.Success).session
        session = (finishDuneRevealTurn(session, 500L) as DuneActionResult.Success).session
        assertTrue(session.turnsComplete)
        assertNull(session.currentPlayer)
        assertEquals(DunePhase.COMBAT, session.phase)
        assertEquals(500L, session.phaseStartedAtMs)
    }

    @Test
    fun playerNamesAreKeptWithTheirColorsAndCombatCompletionAddsRewardsCue() {
        val named = (startDuneSession(
            colors,
            DunePlayerColor.BLUE,
            1L,
            mapOf(DunePlayerColor.BLUE to listOf("  Alice  "), DunePlayerColor.RED to listOf("Bob")),
        ) as DuneActionResult.Success).session
        assertEquals(listOf("Alice"), named.playerNames[DunePlayerColor.BLUE])
        assertEquals(listOf("Bob"), named.playerNames[DunePlayerColor.RED])

        val combat = named.copy(phase = DunePhase.COMBAT)
        val combatComplete = (advanceDunePhase(combat, 2L) as DuneActionResult.Success).session
        assertEquals(DunePhase.COMBAT_COMPLETE, combatComplete.phase)
        assertEquals(listOf("dune_collect_rewards.wav"), duneTransitionClips(combat, combatComplete).map { it.name })
        assertTrue(advanceDunePhase(combatComplete, 15_001L) is DuneActionResult.Error)

        val makers = (advanceDunePhase(combatComplete, 17_000L) as DuneActionResult.Success).session
        assertEquals(DunePhase.MAKERS, makers.phase)
        assertEquals(listOf("dune_phase_makers.wav"), duneTransitionClips(combatComplete, makers).map { it.name })
        assertTrue(
            duneTransitionClips(
                named,
                named.copy(currentPlayer = DunePlayerColor.RED),
            ).isEmpty()
        )
    }

    @Test
    fun endRoundStartsTheNextRoundFromAnyPhase() {
        val started = successfulStart(colors, DunePlayerColor.BLUE, nowMs = 1_000L)
        val ended = (endDuneRound(started.copy(phase = DunePhase.COMBAT), 2_000L) as DuneActionResult.Success).session

        assertEquals(2, ended.round)
        assertEquals(DunePhase.TURNS, ended.phase)
        assertEquals(DunePlayerColor.RED, ended.currentPlayer)
        assertEquals(2_000L, ended.turnStartedAtMs)
    }

    @Test
    fun pauseFreezesTurnTimeUntilResume() {
        val started = successfulStart(colors, DunePlayerColor.BLUE, nowMs = 1_000L)
        val paused = (toggleDunePause(started, 2_000L) as DuneActionResult.Success).session
        assertTrue(paused.paused)
        assertEquals(2_000L, paused.pausedAtMs)
        assertEquals(1_000L, duneGameElapsedMs(paused, 4_000L))

        val resumed = (toggleDunePause(paused, 5_000L) as DuneActionResult.Success).session
        assertFalse(resumed.paused)
        assertNull(resumed.pausedAtMs)
        assertEquals(4_000L, resumed.turnStartedAtMs)
        assertEquals(started.liveSessionStartedAtMs, resumed.liveSessionStartedAtMs)
        assertEquals(2_000L, duneGameElapsedMs(resumed, 6_000L))
    }

    @Test
    fun swordmasterPersistsIntoLaterRounds() {
        var session = successfulStart(colors, DunePlayerColor.BLUE)
        session = (toggleDuneThirdAgent(session, DunePlayerColor.RED) as DuneActionResult.Success).session
        assertEquals(3, session.agentTurnsRemaining[DunePlayerColor.RED])

        val next = (advanceDuneSession(session.copy(phase = DunePhase.RECALL), 10L) as DuneActionResult.Success).session
        assertEquals(3, next.agentTurnsRemaining[DunePlayerColor.RED])
        assertEquals(2, next.agentTurnsRemaining[DunePlayerColor.BLUE])
    }

    @Test
    fun swordmasterTogglesBackOffAfterAMisTap() {
        var session = successfulStart(colors, DunePlayerColor.BLUE)
        session = (toggleDuneThirdAgent(session, DunePlayerColor.RED) as DuneActionResult.Success).session
        session = (toggleDuneThirdAgent(session, DunePlayerColor.RED) as DuneActionResult.Success).session
        assertFalse(DunePlayerColor.RED in session.thirdAgentPlayers)
        assertEquals(2, session.agentTurnsRemaining[DunePlayerColor.RED])

        val next = (advanceDuneSession(session.copy(phase = DunePhase.RECALL), 10L) as DuneActionResult.Success).session
        assertEquals(2, next.agentTurnsRemaining[DunePlayerColor.RED])
    }

    @Test
    fun aDrawnLetterClaimsTheSpaceForWhoeverHasTheTurn() {
        val session = successfulStart(colors, DunePlayerColor.RED)
        val sword = (claimDuneSpace(session, Glyph.SWORDMASTER) as DuneActionResult.Success).session
        assertEquals(setOf(DunePlayerColor.RED), sword.thirdAgentPlayers)
        assertEquals(3, sword.agentTurnsRemaining[DunePlayerColor.RED])

        val council = (claimDuneSpace(session, Glyph.HIGH_COUNCIL) as DuneActionResult.Success).session
        assertEquals(setOf(DunePlayerColor.RED), council.highCouncilPlayers)

        val mentat = (claimDuneSpace(session, Glyph.MENTAT) as DuneActionResult.Success).session
        assertEquals(DunePlayerColor.RED, mentat.mentatHolder)
    }

    @Test
    fun drawingALetterAgainNeverTakesTheSpaceBack() {
        var session = successfulStart(colors, DunePlayerColor.RED)
        Glyph.entries.forEach { glyph ->
            session = (claimDuneSpace(session, glyph) as DuneActionResult.Success).session
            assertTrue("$glyph a second time", claimDuneSpace(session, glyph) is DuneActionResult.Error)
        }
        assertEquals(setOf(DunePlayerColor.RED), session.thirdAgentPlayers)
        assertEquals(setOf(DunePlayerColor.RED), session.highCouncilPlayers)
        assertEquals(DunePlayerColor.RED, session.mentatHolder)
    }

    @Test
    fun aDrawnLetterIsRefusedOutsidePlayerTurns() {
        val session = successfulStart(colors, DunePlayerColor.RED)
        assertTrue(claimDuneSpace(session.copy(phase = DunePhase.COMBAT), Glyph.SWORDMASTER) is DuneActionResult.Error)
        assertTrue(claimDuneSpace(session.copy(paused = true), Glyph.SWORDMASTER) is DuneActionResult.Error)
        assertTrue(claimDuneSpace(DuneSession(), Glyph.SWORDMASTER) is DuneActionResult.Error)
    }

    @Test
    fun mentatGrantsAnAgentForThisRoundOnly() {
        var session = successfulStart(colors, DunePlayerColor.BLUE)
        session = (toggleDuneMentat(session, DunePlayerColor.YELLOW) as DuneActionResult.Success).session
        assertEquals(DunePlayerColor.YELLOW, session.mentatHolder)
        assertEquals(3, session.agentTurnsRemaining[DunePlayerColor.YELLOW])

        // It returns to its board space at the end of the round.
        val next = (advanceDuneSession(session.copy(phase = DunePhase.RECALL), 10L) as DuneActionResult.Success).session
        assertNull(next.mentatHolder)
        assertEquals(2, next.agentTurnsRemaining[DunePlayerColor.YELLOW])
    }

    @Test
    fun mentatIsExclusiveWithinARoundAndReleasable() {
        var session = successfulStart(colors, DunePlayerColor.BLUE)
        session = (toggleDuneMentat(session, DunePlayerColor.YELLOW) as DuneActionResult.Success).session
        assertTrue(toggleDuneMentat(session, DunePlayerColor.RED) is DuneActionResult.Error)

        session = (toggleDuneMentat(session, DunePlayerColor.YELLOW) as DuneActionResult.Success).session
        assertNull(session.mentatHolder)
        assertEquals(2, session.agentTurnsRemaining[DunePlayerColor.YELLOW])

        session = (toggleDuneMentat(session, DunePlayerColor.RED) as DuneActionResult.Success).session
        assertEquals(DunePlayerColor.RED, session.mentatHolder)
        assertEquals(3, session.agentTurnsRemaining[DunePlayerColor.RED])
    }

    @Test
    fun swordmasterAndMentatStack() {
        var session = successfulStart(colors, DunePlayerColor.BLUE)
        session = (toggleDuneThirdAgent(session, DunePlayerColor.RED) as DuneActionResult.Success).session
        session = (toggleDuneMentat(session, DunePlayerColor.RED) as DuneActionResult.Success).session
        assertEquals(4, session.agentTurnsRemaining[DunePlayerColor.RED])
        assertEquals(3, duneAgentsPerRound(session, DunePlayerColor.RED))
    }

    @Test
    fun mentatGivesTheHolderAnExtraTurnInTheRotation() {
        var session = successfulStart(colors, DunePlayerColor.BLUE)
        session = (toggleDuneMentat(session, DunePlayerColor.BLUE) as DuneActionResult.Success).session
        // Blue burns all three agents; the rotation must keep coming back to them.
        var turns = 0
        while ((session.agentTurnsRemaining[DunePlayerColor.BLUE] ?: 0) > 0) {
            if (session.currentPlayer == DunePlayerColor.BLUE) {
                session = (endDuneAgentTurn(session, 1L) as DuneActionResult.Success).session
                turns++
            } else {
                session = (finishDuneRevealTurn(session, 1L) as DuneActionResult.Success).session
            }
            if (turns > 10) break
        }
        assertEquals(3, turns)
    }

    @Test
    fun winnerMustBeSeatedAndGetsAnExpiry() {
        val started = successfulStart(colors, DunePlayerColor.BLUE, nowMs = 1_000L)
        assertTrue(endDuneSession(started, DunePlayerColor.GREEN, 4_000L, 300_000L) is DuneActionResult.Error)

        val ended = (endDuneSession(started, DunePlayerColor.RED, 4_000L, 300_000L) as DuneActionResult.Success).session
        assertEquals(DunePlayerColor.RED, ended.winner)
        assertEquals(4_000L, ended.endedAtMs)
        assertEquals(304_000L, ended.winnerEndsAtMs)
        assertTrue(ended.showingWinner)
        assertEquals(3_000L, duneGameElapsedMs(ended, 100_000L))

        val paused = (toggleDunePause(started, 2_000L) as DuneActionResult.Success).session
        val endedWhilePaused = (endDuneSession(paused, DunePlayerColor.RED, 9_000L, 300_000L) as DuneActionResult.Success).session
        assertEquals(1_000L, duneGameElapsedMs(endedWhilePaused, 100_000L))
    }

    @Test
    fun milestoneRoundWaitsForACompleteScoreCheckpoint() {
        val collecting = successfulStart(colors, DunePlayerColor.BLUE).copy(
            round = 5, phase = DunePhase.COMBAT_COMPLETE, phaseStartedAtMs = 1L,
        )
        assertTrue(collecting.checkpointRequired)
        assertTrue(advanceDunePhase(collecting, 20_000L) is DuneActionResult.Error)
        assertTrue(recordDuneScoreCheckpoint(collecting, mapOf(DunePlayerColor.BLUE to 7), 2L) is DuneActionResult.Error)
        val points = mapOf(DunePlayerColor.BLUE to 8, DunePlayerColor.RED to 6, DunePlayerColor.YELLOW to 7)
        val recorded = (recordDuneScoreCheckpoint(collecting, points, 2L) as DuneActionResult.Success).session
        assertFalse(recorded.checkpointRequired)
        assertTrue(advanceDunePhase(recorded, 20_000L) is DuneActionResult.Success)
    }

    @Test
    fun finalCheckpointRequiresAndRecordsWinnerAtomically() {
        val final = successfulStart(colors, DunePlayerColor.BLUE).copy(round = 10, phase = DunePhase.COMBAT_COMPLETE)
        val points = mapOf(DunePlayerColor.BLUE to 10, DunePlayerColor.RED to 12, DunePlayerColor.YELLOW to 9)
        assertTrue(recordDuneScoreCheckpoint(final, points, 5L, final = true) is DuneActionResult.Error)
        val ended = (recordDuneScoreCheckpoint(final, points, 5L, final = true, winner = DunePlayerColor.RED) as DuneActionResult.Success).session
        assertEquals(DunePlayerColor.RED, ended.winner)
        assertTrue(ended.scoreCheckpoints.single().final)
    }

    @Test
    fun highCouncilCanBeCorrectedWithAToggle() {
        val started = successfulStart(colors, DunePlayerColor.BLUE)
        val marked = (toggleDuneHighCouncil(started, DunePlayerColor.YELLOW) as DuneActionResult.Success).session
        assertTrue(DunePlayerColor.YELLOW in marked.highCouncilPlayers)
        val cleared = (toggleDuneHighCouncil(marked, DunePlayerColor.YELLOW) as DuneActionResult.Success).session
        assertFalse(DunePlayerColor.YELLOW in cleared.highCouncilPlayers)
    }

    @Test
    fun cueNamesAreStable() {
        val round = successfulStart(colors, DunePlayerColor.BLUE).copy(round = 7)
        assertEquals(
            listOf("dune_conflict_07.wav", "dune_name_blue.wav", DUNE_PHRASE_FIRST_PLAYER),
            duneConflictClips(round).map { it.name },
        )
        assertEquals("dune_winner_yellow.wav", duneWinnerCueFilename(DunePlayerColor.YELLOW))
        assertEquals("dune_game_hour_1.wav", duneHourlyCueFilename(1))
        assertEquals("dune_game_hour_5.wav", duneHourlyCueFilename(5))
        assertEquals("dune_game_another_hour.wav", duneHourlyCueFilename(6))
        assertEquals("dune_game_another_hour.wav", duneHourlyCueFilename(12))
    }

    // The load-bearing test for turn undo. Every other one below checks a specific field;
    // this one checks *all* of them, so a field added to the turn-end path later and forgotten
    // in DuneTurnUndo fails here rather than at the table. If it breaks, the fix is to carry
    // the new field through the undo record, not to relax the assertion.
    @Test
    fun undoingATurnRestoresEverythingTheTurnEndTouched() {
        val before = successfulStart(colors, DunePlayerColor.BLUE, nowMs = 100L)
            .copy(fiveMinuteWarningPlayed = true, undoAvailable = true)

        val ended = (endDuneAgentTurn(before, 9_000L) as DuneActionResult.Success).session
        val undone = (undoDuneTurn(ended) as DuneActionResult.Success).session
        assertEquals(before, undone)

        val revealEnded = (finishDuneRevealTurn(before, 9_000L) as DuneActionResult.Success).session
        assertEquals(before, (undoDuneTurn(revealEnded) as DuneActionResult.Success).session)
    }

    @Test
    fun undoHandsTheTurnBackToThePlayerWhoLostIt() {
        val started = successfulStart(colors, DunePlayerColor.BLUE, nowMs = 100L)
        val ended = (endDuneAgentTurn(started, 200L) as DuneActionResult.Success).session
        assertEquals(DunePlayerColor.RED, ended.currentPlayer)
        assertEquals(1, ended.agentTurnsRemaining[DunePlayerColor.BLUE])

        val undone = (undoDuneTurn(ended) as DuneActionResult.Success).session
        assertEquals(DunePlayerColor.BLUE, undone.currentPlayer)
        assertEquals(2, undone.agentTurnsRemaining[DunePlayerColor.BLUE])
        assertEquals(100L, undone.turnStartedAtMs)
        // One level only — undoing twice must not walk back through the round.
        assertTrue(undoDuneTurn(undone) is DuneActionResult.Error)
    }

    @Test
    fun undoingTheFinalRevealComesBackOutOfCombat() {
        var session = successfulStart(colors, DunePlayerColor.BLUE, nowMs = 100L)
        session = (finishDuneRevealTurn(session, 200L) as DuneActionResult.Success).session
        session = (finishDuneRevealTurn(session, 300L) as DuneActionResult.Success).session
        session = (finishDuneRevealTurn(session, 400L) as DuneActionResult.Success).session
        assertEquals(DunePhase.COMBAT, session.phase)
        assertTrue(session.turnsComplete)

        // The reveal that ends the round is the one a misfired shake most likely faked, so it
        // has to be reversible even though it changed phase.
        val undone = (undoDuneTurn(session) as DuneActionResult.Success).session
        assertEquals(DunePhase.TURNS, undone.phase)
        assertEquals(DunePlayerColor.YELLOW, undone.currentPlayer)
        assertFalse(undone.turnsComplete)
        assertFalse(DunePlayerColor.YELLOW in undone.revealedPlayers)
    }

    @Test
    fun undoDoesNotRevokeATokenClaimedAfterTheTurnEnded() {
        // The reason the undo record stores deltas rather than a snapshot: Swordmaster,
        // Mentat and High Council all stay legal after a turn ends, and a snapshot restore
        // would silently take back an agent granted in between.
        val started = successfulStart(colors, DunePlayerColor.BLUE, nowMs = 100L)
        val ended = (endDuneAgentTurn(started, 200L) as DuneActionResult.Success).session
        val withSword = (toggleDuneThirdAgent(ended, DunePlayerColor.BLUE) as DuneActionResult.Success).session
        assertEquals(2, withSword.agentTurnsRemaining[DunePlayerColor.BLUE])

        val undone = (undoDuneTurn(withSword) as DuneActionResult.Success).session
        assertEquals(3, undone.agentTurnsRemaining[DunePlayerColor.BLUE])
        assertTrue(DunePlayerColor.BLUE in undone.thirdAgentPlayers)
    }

    @Test
    fun undoCannotInventAnAgentTheTurnEndNeverSpent() {
        // Agent counts floor at zero, so a turn ended with none left spent nothing. Undo has
        // to give back nothing, or repeated flip-then-undo would mint agents.
        val spent = successfulStart(colors, DunePlayerColor.BLUE, nowMs = 100L)
            .let { it.copy(agentTurnsRemaining = it.agentTurnsRemaining + (DunePlayerColor.BLUE to 0)) }
        val ended = (endDuneAgentTurn(spent, 200L) as DuneActionResult.Success).session
        assertEquals(0, ended.agentTurnsRemaining[DunePlayerColor.BLUE])

        val undone = (undoDuneTurn(ended) as DuneActionResult.Success).session
        assertEquals(0, undone.agentTurnsRemaining[DunePlayerColor.BLUE])
    }

    @Test
    fun aRevealedPlayerIsNotUnrevealedByUndoingSomeoneElsesTurn() {
        var session = successfulStart(colors, DunePlayerColor.BLUE, nowMs = 100L)
        session = (finishDuneRevealTurn(session, 200L) as DuneActionResult.Success).session
        // BLUE has revealed; RED now ends an agent turn, and the rotation steps over BLUE.
        session = (endDuneAgentTurn(session, 300L) as DuneActionResult.Success).session

        val undone = (undoDuneTurn(session) as DuneActionResult.Success).session
        assertEquals(DunePlayerColor.RED, undone.currentPlayer)
        assertTrue(DunePlayerColor.BLUE in undone.revealedPlayers)
    }

    @Test
    fun aNewRoundDropsTheTurnUndoFromTheOldOne() {
        var session = successfulStart(colors, DunePlayerColor.BLUE, nowMs = 100L)
        session = (endDuneAgentTurn(session, 200L) as DuneActionResult.Success).session
        val next = (advanceDuneSession(session.copy(phase = DunePhase.RECALL), 5_000L) as DuneActionResult.Success).session
        // Round two is back in TURNS, which the phase guard accepts — so the record itself has
        // to be gone or undo would drag the table back into round one.
        assertNull(next.turnUndo)
        assertTrue(undoDuneTurn(next) is DuneActionResult.Error)
    }

    @Test
    fun undoIsRefusedOnceTheRoundHasMovedPastCombat() {
        var session = successfulStart(colors, DunePlayerColor.BLUE, nowMs = 100L)
        session = (finishDuneRevealTurn(session, 200L) as DuneActionResult.Success).session
        session = (finishDuneRevealTurn(session, 300L) as DuneActionResult.Success).session
        session = (finishDuneRevealTurn(session, 400L) as DuneActionResult.Success).session
        val resolved = (advanceDunePhase(session, 500L) as DuneActionResult.Success).session
        assertEquals(DunePhase.COMBAT_COMPLETE, resolved.phase)
        assertTrue(undoDuneTurn(resolved) is DuneActionResult.Error)
    }

    @Test
    fun undoNamesTheRestoredPlayerAloud() {
        val named = (startDuneSession(
            colors,
            DunePlayerColor.BLUE,
            100L,
            mapOf(DunePlayerColor.BLUE to listOf("Nina")),
        ) as DuneActionResult.Success).session
        val ended = (endDuneAgentTurn(named, 200L) as DuneActionResult.Success).session
        assertEquals(
            listOf("dune_name_nina.wav", DUNE_PHRASE_TURN_RESTORED),
            duneTurnRestoredClips(named, ended.turnUndo!!.player).map { it.name },
        )
    }

    @Test
    fun timerWarningsAreJustTheElapsedTime() {
        assertEquals(listOf("dune_timer_five.wav"), duneTimerClips(DuneTimerMark.FIVE).map { it.name })
        assertEquals(listOf("dune_timer_seven.wav"), duneTimerClips(DuneTimerMark.SEVEN).map { it.name })
        assertEquals(listOf("dune_timer_ten.wav"), duneTimerClips(DuneTimerMark.TEN).map { it.name })
    }

    @Test
    fun onlyTheCombatBeatsCarryMusic() {
        val session = successfulStart(colors, DunePlayerColor.BLUE, nowMs = 100L)
        fun bedFor(from: DunePhase, to: DunePhase) =
            duneTransitionBed(session.copy(phase = from), session.copy(phase = to))?.name

        assertEquals(DUNE_BED_COMBAT, bedFor(DunePhase.TURNS, DunePhase.COMBAT))
        assertEquals(DUNE_BED_REWARDS, bedFor(DunePhase.COMBAT, DunePhase.COMBAT_COMPLETE))
        assertNull(bedFor(DunePhase.COMBAT_COMPLETE, DunePhase.MAKERS))
        assertNull(bedFor(DunePhase.MAKERS, DunePhase.RECALL))
        // A poll that re-reports the same phase must not restart the sting.
        assertNull(bedFor(DunePhase.COMBAT, DunePhase.COMBAT))
        // The winner fanfare owns the moment; a combat sting under it would be a pile-up.
        assertNull(
            duneTransitionBed(
                session.copy(phase = DunePhase.TURNS),
                session.copy(phase = DunePhase.COMBAT, winner = DunePlayerColor.BLUE),
            )?.name
        )
    }

    private fun successfulStart(
        colors: List<DunePlayerColor>,
        first: DunePlayerColor,
        nowMs: Long = 1L,
    ): DuneSession = (startDuneSession(colors, first, nowMs) as DuneActionResult.Success).session
}
