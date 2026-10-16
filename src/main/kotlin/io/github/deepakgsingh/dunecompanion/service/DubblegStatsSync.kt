package io.github.deepakgsingh.dunecompanion.service

import io.github.deepakgsingh.dunecompanion.DUBBLEG_BASE
import io.github.deepakgsingh.dunecompanion.DubblegConnection
import io.github.deepakgsingh.dunecompanion.DuneHistoryStore
import io.github.deepakgsingh.dunecompanion.DubblegShareStore
import io.github.deepakgsingh.dunecompanion.DubblegUploadResult
import io.github.deepakgsingh.dunecompanion.parseDubblegUploadResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Fixed HTTPS origin, no redirects: credentials cannot be forwarded to another server. */
private fun request(path: String, token: String, body: JSONObject? = null): Pair<Int, String> {
    val connection = URL(DUBBLEG_BASE + path).openConnection() as HttpsURLConnection
    try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(2048)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= 16_384) { "Response too large" }
                output.write(buffer, 0, count)
            }
            String(output.toByteArray(), Charsets.UTF_8)
        }.orEmpty()
        return code to response
    } finally { connection.disconnect() }
}

suspend fun verifyDubblegCredential(token: String): DubblegConnection.Credential = withContext(Dispatchers.IO) {
    require(Regex("dgstats_[A-Za-z0-9_-]{43}").matches(token)) { "Paste a stats credential from DubbleG's Stats devices page." }
    val (code, body) = request("/api/companion/connection", token)
    check(code in 200..299) {
        if (code == 401 || code == 403) "Credential rejected or revoked. Ask for a new one."
        else "Could not connect to DubbleG ($code). Try again later."
    }
    val json = JSONObject(body)
    check(json.optString("scope") == "casual-history:write" && Regex("[a-f0-9]{32}").matches(json.optString("device_id"))) { "Unexpected DubbleG connection response" }
    DubblegConnection.Credential(token, json.getString("device_id"), json.getString("name"))
}

suspend fun runDubblegStatsSync(connection: DubblegConnection, shares: DubblegShareStore, history: DuneHistoryStore) {
    while (currentCoroutineContext().isActive) {
        // Consent is archived with the winner, so a crash between archive and outbox writes
        // cannot lose the request. Existing jobs, including blocked ones, remain untouched.
        shares.syncConsentedGames(history)
        val credential = connection.credential()
        if (credential != null) {
            val job = shares.due(credential.deviceId, System.currentTimeMillis())
            if (job != null) {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val (code, body) = request("/api/casual/history", credential.token, job.second.getJSONObject("body"))
                        parseDubblegUploadResponse(code, body)
                    }.getOrDefault(DubblegUploadResult.Retry)
                }
                shares.complete(job.first, result, System.currentTimeMillis())
            }
        }
        delay(2000L)
    }
}
