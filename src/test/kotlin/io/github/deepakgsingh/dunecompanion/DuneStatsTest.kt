package io.github.deepakgsingh.dunecompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DuneStatsTest {

    @get:Rule val folder = TemporaryFolder()

    private val blue = DunePlayerColor.BLUE
    private val red = DunePlayerColor.RED

    private fun turn(id: Long, round: Int, seat: DunePlayerColor, kind: DuneTurnKind, seconds: Long) =
        DuneTurnRecord(id, round, seat, seat.displayName, kind, id * 1000, id * 1000 + seconds * 1000, seconds * 1000)

    private fun finishedGame(startedAt: Long = 1_000L, winner: DunePlayerColor = red) = DuneSession(
        active = true,
        playerColors = listOf(blue, red),
        playerNames = mapOf(blue to listOf("Nina"), red to listOf("Omar", "Lee")),
        round = 2,
        gameStartedAtMs = startedAt,
        liveSessionStartedAtMs = startedAt,
        endedAtMs = startedAt + 3_600_000L,
        winner = winner,
        scoreCheckpoints = listOf(
            DuneScoreCheckpoint(2, 5L, mapOf(blue to 7, red to 9), final = true, winner = winner),
        ),
        turnRecords = listOf(
            turn(1, 1, blue, DuneTurnKind.AGENT, 60),
            turn(2, 1, red, DuneTurnKind.AGENT, 170),
            turn(3, 1, blue, DuneTurnKind.REVEAL, 30),
            turn(4, 1, red, DuneTurnKind.REVEAL, 30),
            turn(5, 2, red, DuneTurnKind.AGENT, 310),
        ),
    )

    @Test
    fun gameStatsMatchTheTurnRecords() {
        val stats = duneGameStats(duneHistoryJson(finishedGame()))
        assertEquals(5, stats.getInt("turns"))
        assertEquals(600_000L, stats.getLong("turnTimeMs"))
        assertEquals(120_000L, stats.getLong("averageTurnMs"))
        assertEquals(3_600_000L, stats.getLong("gameMs"))
        assertEquals("Omar & Lee", stats.getString("winnerName"))

        // Slowest seat first.
        val seats = stats.getJSONArray("seats")
        val first = seats.getJSONObject(0)
        assertEquals("red", first.getString("seat"))
        assertEquals(3, first.getInt("turns"))
        assertEquals(510_000L, first.getLong("totalMs"))
        assertEquals(0.85, first.getDouble("share"), 0.0001)
        assertEquals(310_000L, first.getLong("longestMs"))
        assertEquals(9, first.getInt("victoryPoints"))
        assertTrue(first.getBoolean("winner"))
        assertFalse(seats.getJSONObject(1).getBoolean("winner"))

        val kinds = stats.getJSONArray("kinds")
        assertEquals(3, kinds.getJSONObject(0).getInt("turns"))
        assertEquals(60_000L, kinds.getJSONObject(1).getLong("totalMs"))

        val rounds = stats.getJSONArray("roundTimes")
        assertEquals(290_000L, rounds.getJSONObject(0).getLong("turnTimeMs"))
        assertEquals(2, stats.getInt("slowestRound"))
        assertEquals(2, stats.getJSONObject("longestTurn").getInt("round"))
    }

    @Test
    fun anEmptyGameHasNoDivisionByZero() {
        val stats = duneGameStats(duneHistoryJson(DuneSession(playerColors = listOf(blue, red))))
        assertEquals(0, stats.getInt("turns"))
        assertEquals(0L, stats.getLong("averageTurnMs"))
        assertEquals(0.0, stats.getJSONArray("seats").getJSONObject(0).getDouble("share"), 0.0)
        assertTrue(stats.isNull("longestTurn"))
    }

    @Test
    fun allTimeStatsTallyByNameAndSkipColourSeats() {
        val first = duneHistoryJson(finishedGame(startedAt = 1_000L, winner = red))
        val second = duneHistoryJson(
            finishedGame(startedAt = 9_000_000L, winner = blue).copy(playerNames = mapOf(blue to listOf("nina ")))
        )
        val all = duneAllTimeStats(listOf(first, second))
        assertEquals(2, all.getInt("games"))
        val players = all.getJSONArray("players")
        // Nina played both; the red seat was unnamed the second time, so only one Omar & Lee game.
        assertEquals(2, players.length())
        val nina = players.getJSONObject(0)
        assertEquals("nina", nina.getString("name"))
        assertEquals(2, nina.getInt("games"))
        assertEquals(1, nina.getInt("wins"))
        assertEquals(0.5, nina.getDouble("winRate"), 0.0)
        assertEquals(7.0, nina.getDouble("averagePoints"), 0.0)
    }

    @Test
    fun historyStoreKeepsOneRecordPerGameAndSurvivesReopening() {
        val file = folder.newFile("history.json").apply { delete() }
        val store = DuneHistoryStore(file)
        val record = duneHistoryJson(finishedGame())
        assertTrue(store.appendIfNew(record))
        assertFalse(store.appendIfNew(duneHistoryJson(finishedGame())))
        assertTrue(store.appendIfNew(duneHistoryJson(finishedGame(startedAt = 2_000L))))

        val reopened = DuneHistoryStore(file)
        assertEquals(2, reopened.records().size)
        assertEquals(5, reopened.find("game-1000")!!.getJSONArray("turns").length())
        assertEquals(2, duneGameSummaries(reopened.records()).length())
        assertEquals("game-2000", duneGameSummaries(reopened.records()).getJSONObject(0).getString("sessionId"))

        assertTrue(reopened.delete("game-1000"))
        assertFalse(reopened.delete("game-1000"))
        assertEquals(listOf("game-2000"), store.records().map { it.getString("session_id") })
    }
}
