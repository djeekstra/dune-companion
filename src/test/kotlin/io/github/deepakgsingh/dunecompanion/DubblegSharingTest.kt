package io.github.deepakgsingh.dunecompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DubblegSharingTest {
    @get:Rule val folder = TemporaryFolder()
    private val phone = "a".repeat(32)
    private val otherPhone = "b".repeat(32)
    private fun record() = JSONObject().put("session_id", "game-1000").put("started_at_ms", 1000L)
        .put("schema_version", 1).put("game_type", "dune-imperium").put("finished", true)
        .put("winner", "blue").put("players", JSONArray()).put("turns", JSONArray())
        .put("elapsed_game_ms", 3_600_000L).put("credential", "must-not-upload")
        .put("share_url", "must-not-upload").put("share_device_id", phone).put("edition", 51)

    @Test fun uploadContainsOnlyApprovedDataAndNamespacesPhones() {
        val original = record()
        val upload = dubblegUploadBody(original, phone)
        assertEquals("casual-$phone-1000", upload.getString("session_id"))
        assertEquals("casual", upload.getString("provenance"))
        assertFalse(upload.has("edition"))
        assertFalse(upload.has("credential"))
        assertFalse(upload.has("share_url"))
        assertFalse(upload.has("share_device_id"))
        assertEquals("game-1000", original.getString("session_id"))
        assertNotEquals(upload.getString("session_id"), dubblegUploadBody(original, otherPhone).getString("session_id"))
    }

    @Test fun incompleteAndTestGamesCannotBeQueued() {
        for (invalid in listOf(record().put("finished", false), record().put("winner", ""), record().put("test_mode", true))) {
            assertThrows(IllegalArgumentException::class.java) { dubblegUploadBody(invalid, phone) }
        }
    }

    @Test fun retrySurvivesRestartAndKeepsTheApprovedSnapshot() {
        val file = folder.newFile("shares.json")
        file.delete()
        val first = DubblegShareStore(file)
        assertNull(first.due(phone, 0L)) // all history stays local without an explicit share
        first.queue(record(), phone)
        first.complete("game-1000", DubblegUploadResult.Retry, 100L)
        val reopened = DubblegShareStore(file)
        assertNull(reopened.due(phone, 2099L))
        assertEquals(3_600_000L, reopened.due(phone, 2100L)!!.second.getJSONObject("body").getLong("elapsed_game_ms"))
        assertNull(reopened.due(otherPhone, 3000L))
        reopened.complete("game-1000", DubblegUploadResult.Blocked("Reconnect"), 3000L)
        assertNull(reopened.due(phone, 9000L))
        reopened.queue(record().put("elapsed_game_ms", 1L), phone)
        assertEquals(3_600_000L, reopened.due(phone, 9000L)!!.second.getJSONObject("body").getLong("elapsed_game_ms"))
        assertThrows(IllegalArgumentException::class.java) { reopened.queue(record(), otherPhone) }
        assertFalse(file.readText().contains("must-not-upload"))
    }

    @Test fun successfulSkippedAndRevokedGamesStopUploading() {
        for (result in listOf(DubblegUploadResult.Shared("https://dubbleg.org/g/abc"), DubblegUploadResult.Skipped("Under 60 min"), DubblegUploadResult.Revoked)) {
            val file = folder.newFile(); file.delete()
            val store = DubblegShareStore(file)
            store.queue(record(), phone)
            store.complete("game-1000", result, 0L)
            store.queue(record(), phone)
            assertNull(DubblegShareStore(file).due(phone, 100_000L))
            assertNotEquals("pending", store.status("game-1000").getString("status"))
        }
    }

    @Test fun consentIsLocalPerGameAndArchivedForCrashRecovery() {
        val colors = listOf(DunePlayerColor.BLUE, DunePlayerColor.GREEN, DunePlayerColor.RED)
        val started = (startDuneSession(colors, colors.first(), 1000L) as DuneActionResult.Success).session
        assertNull(started.statsDeviceId)
        assertFalse(duneHistoryJson(started).has("share_device_id"))
        val consented = started.copy(statsDeviceId = phone)
        val next = (endDuneAgentTurn(consented, 2000L) as DuneActionResult.Success).session
        assertEquals(phone, next.statsDeviceId)
        val finished = (endDuneSession(next, colors.first(), 3000L, 5000L) as DuneActionResult.Success).session
        val game = duneHistoryJson(finished)
        assertEquals(phone, game.getString("share_device_id"))
        val historyFile = folder.newFile(); historyFile.delete()
        val history = DuneHistoryStore(historyFile)
        history.appendIfNew(game)
        val shareFile = folder.newFile(); shareFile.delete()
        val store = DubblegShareStore(shareFile)
        store.syncConsentedGames(history)
        assertNotNull(store.due(phone, 0L))
        store.complete(game.getString("session_id"), DubblegUploadResult.Blocked("Reconnect"), 0L)
        DubblegShareStore(shareFile).syncConsentedGames(history)
        assertNull(store.due(phone, 0L)) // recovery must not restart a rejected upload
        store.deleteLocalGame(history, game.getString("session_id"))
        store.syncConsentedGames(history)
        assertNull(store.due(phone, 0L))
        assertTrue(history.records().isEmpty())
    }

    @Test fun deletingLocalGameCancelsQueuedWork() {
        val file = folder.newFile(); file.delete()
        val store = DubblegShareStore(file)
        store.queue(record(), phone)
        store.forget("game-1000")
        assertNull(store.due(phone, 0L))
    }

    @Test fun responseDistinguishesPermanentFailuresFromOfflineAndLimitsLinks() {
        assertTrue(parseDubblegUploadResponse(401, "") is DubblegUploadResult.Blocked)
        assertTrue(parseDubblegUploadResponse(400, "") is DubblegUploadResult.Blocked)
        assertEquals(DubblegUploadResult.Retry, parseDubblegUploadResponse(429, ""))
        assertEquals(DubblegUploadResult.Retry, parseDubblegUploadResponse(503, ""))
        assertEquals(DubblegUploadResult.Retry, parseDubblegUploadResponse(200, "{}"))
        assertEquals(DubblegUploadResult.Retry, parseDubblegUploadResponse(200, "{\"url\":\"https://evil.test/g/a\"}"))
        assertEquals(DubblegUploadResult.Retry, parseDubblegUploadResponse(200, "{\"url\":null}"))
        assertEquals(DubblegUploadResult.Revoked, parseDubblegUploadResponse(200, "{\"url\":null,\"revoked\":true}"))
        assertEquals(DubblegUploadResult.Skipped("Under 60 min"), parseDubblegUploadResponse(200, "{\"url\":null,\"skipped\":\"too_short\",\"skipped_note\":\"Under 60 min\"}"))
        assertEquals(DubblegUploadResult.Shared("https://dubbleg.org/g/abc"), parseDubblegUploadResponse(201, "{\"url\":\"https://dubbleg.org/g/abc\"}"))
    }
}
