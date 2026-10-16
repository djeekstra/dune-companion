package io.github.deepakgsingh.dunecompanion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.media.audiofx.LoudnessEnhancer
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.deepakgsingh.dunecompanion.ClipSpec
import io.github.deepakgsingh.dunecompanion.Config
import io.github.deepakgsingh.dunecompanion.AudioSequence
import io.github.deepakgsingh.dunecompanion.DuneActionResult
import io.github.deepakgsingh.dunecompanion.DunePhase
import io.github.deepakgsingh.dunecompanion.DuneSession
import io.github.deepakgsingh.dunecompanion.DubblegConnection
import io.github.deepakgsingh.dunecompanion.DubblegShareStore
import io.github.deepakgsingh.dunecompanion.DuneHistoryStore
import io.github.deepakgsingh.dunecompanion.DuneSessionStore
import io.github.deepakgsingh.dunecompanion.duneAutoAdvance
import io.github.deepakgsingh.dunecompanion.duneHistoryJson
import io.github.deepakgsingh.dunecompanion.DuneTimerMark
import io.github.deepakgsingh.dunecompanion.DuneTone
import io.github.deepakgsingh.dunecompanion.Steward
import io.github.deepakgsingh.dunecompanion.advanceDunePhase
import io.github.deepakgsingh.dunecompanion.clipFile
import io.github.deepakgsingh.dunecompanion.duneGameElapsedMs
import io.github.deepakgsingh.dunecompanion.duneHourlyCueFilename
import io.github.deepakgsingh.dunecompanion.duneSpeechFor
import io.github.deepakgsingh.dunecompanion.duneTimerClips
import io.github.deepakgsingh.dunecompanion.duneTone
import io.github.deepakgsingh.dunecompanion.duneTransitionBed
import io.github.deepakgsingh.dunecompanion.duneTransitionClips
import io.github.deepakgsingh.dunecompanion.markDuneTimerWarning
import io.github.deepakgsingh.dunecompanion.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Foreground service that is the steward: game timers, the audio queue, gestures, and the admin
 * panel. The phone's own text-to-speech stands in for any clip that is not installed.
 */
class StewardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val audioQueue = Channel<AudioSequence>(
        capacity = Config.AUDIO_QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    @Volatile private var playbackJob: Job? = null
    @Volatile private var bedPlayer: MediaPlayer? = null

    // Voice packs, if a table records one, go here: filesDir/clips/<name>.<ext>. Anything
    // installed wins over speech; see DuneSpeech for the names.
    private val clipsDir by lazy { File(filesDir, "clips").apply { mkdirs() } }
    private val duneStore by lazy { DuneSessionStore(applicationContext) }
    private val historyStore by lazy { DuneHistoryStore(applicationContext) }
    private val dubblegConnection by lazy { DubblegConnection(applicationContext) }
    private val shareStore by lazy { DubblegShareStore(File(filesDir, "dubbleg_shares.json")) }
    private val panelServer by lazy {
        PanelServer(clipsDir, historyStore, assets.open("panel.html").bufferedReader().use { it.readText() }, dubblegConnection, shareStore)
    }

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private val pendingUtterances = ConcurrentHashMap<String, () -> Unit>()
    private var nextUtteranceId = 0L

    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)

        Steward.postDune(duneStore.load())

        tts = TextToSpeech(applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setAudioAttributes(speechAttributes())
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) = Unit
                    override fun onDone(utteranceId: String) { pendingUtterances.remove(utteranceId)?.invoke() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String) { pendingUtterances.remove(utteranceId)?.invoke() }
                })
            }
            Log.i(TAG, "tts ready=$ttsReady")
        }

        runCatching { panelServer.start() }.onFailure { Log.e(TAG, "panel server failed to start", it) }
        registerNsd()

        scope.launch(Dispatchers.Main) { runAudioWorker() }
        scope.launch { Steward.audioRequest.collect { enqueue(it) } }
        scope.launch { Steward.audioReset.collect { resetAudio() } }
        scope.launch {
            Steward.dune.drop(1).collect { session ->
                duneStore.save(session)
                // A game joins the history the moment it has a winner. Idempotent per game.
                if (session.winner != null) historyStore.appendIfNew(duneHistoryJson(session))
            }
        }
        scope.launch { runDubblegStatsSync(dubblegConnection, shareStore, historyStore) }
        scope.launch { watchDuneTurnTimer() }
        scope.launch { watchDuneAutomatedPhases() }
        scope.launch { watchPanelUrls() }
        scope.launch(Dispatchers.Main) { runGestureWatcher(applicationContext) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The game is kept, clocks frozen, for the next launch to pick up where it was.
            quitApp(endGame = false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // The save loop is cancelled below and may not have caught the last change: a quit's
        // pause or reset lands a moment before this. Written here so it cannot be lost.
        duneStore.save(Steward.dune.value)
        runCatching { nsdListener?.let { nsd?.unregisterService(it) } }
        panelServer.stop()
        audioQueue.close()
        tts?.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- Game watchers -----------------------------------------------------------------

    private suspend fun watchDuneTurnTimer() {
        while (currentCoroutineContext().isActive) {
            var session = Steward.dune.value
            if (session.active && !session.showingWinner && !session.paused && session.gameStartedAtMs > 0L) {
                val elapsedHours = (duneGameElapsedMs(session, System.currentTimeMillis()) / 3_600_000L).toInt()
                if (elapsedHours > session.hourlyGameCueCount) {
                    session = session.copy(hourlyGameCueCount = elapsedHours)
                    Steward.postDune(session)
                    Steward.emitAudioClips(listOf(ClipSpec(duneHourlyCueFilename(elapsedHours), boostMillibels = 600)))
                }
            }
            if (
                session.active && !session.showingWinner && session.phase == DunePhase.TURNS &&
                !session.paused && session.currentPlayer != null && session.turnStartedAtMs > 0L
            ) {
                val elapsed = System.currentTimeMillis() - session.turnStartedAtMs
                withDuneLock {
                    // Re-read under the lock: a turn end that landed since the read above
                    // must not be overwritten by a stale session carrying the warning flag.
                    val current = Steward.dune.value
                    if (current.turnStartedAtMs != session.turnStartedAtMs) return@withDuneLock
                    when {
                        elapsed >= Config.DUNE_TIMER_TEN_MINUTES_MS && !current.tenMinuteWarningPlayed -> {
                            Steward.postDune(markDuneTimerWarning(current, fiveMinutes = true, sevenMinutes = true, tenMinutes = true))
                            Steward.emitAudioClips(duneTimerClips(DuneTimerMark.TEN))
                        }
                        elapsed >= Config.DUNE_TIMER_PULSE_MS && !current.sevenMinuteWarningPlayed -> {
                            Steward.postDune(markDuneTimerWarning(current, fiveMinutes = true, sevenMinutes = true))
                            Steward.emitAudioClips(duneTimerClips(DuneTimerMark.SEVEN))
                        }
                        elapsed >= Config.DUNE_TIMER_FIVE_MINUTES_MS && !current.fiveMinuteWarningPlayed -> {
                            Steward.postDune(markDuneTimerWarning(current, fiveMinutes = true))
                            Steward.emitAudioClips(duneTimerClips(DuneTimerMark.FIVE))
                        }
                    }
                }
            }
            delay(1_000L)
        }
    }

    private suspend fun watchDuneAutomatedPhases() {
        while (currentCoroutineContext().isActive) {
            withDuneLock {
                val session = Steward.dune.value
                val nowMs = System.currentTimeMillis()
                // Clear the winner card here rather than in the UI: the display may not be open.
                val winnerExpired = session.showingWinner &&
                    session.winnerEndsAtMs?.let { nowMs >= it } == true
                if (winnerExpired) {
                    Steward.postDune(DuneSession())
                    return@withDuneLock
                }
                val revealTurnsComplete = session.phase == DunePhase.TURNS && session.turnsComplete
                val automatedPhaseReady = duneAutoAdvance(session)?.let { nowMs >= it.dueAtMs } == true
                if (
                    session.active && !session.showingWinner &&
                    !session.paused && (revealTurnsComplete || automatedPhaseReady)
                ) {
                    val result = advanceDunePhase(session, nowMs)
                    if (result is DuneActionResult.Success) {
                        Steward.postDune(result.session)
                        Steward.emitAudioClips(
                            duneTransitionClips(session, result.session),
                            duneTransitionBed(session, result.session),
                        )
                    }
                }
            }
            delay(250L)
        }
    }

    // ---- Discovery -----------------------------------------------------------------------

    // Re-read every few seconds: the address changes when the phone switches Wi-Fi or starts
    // a hotspot, and the display has to show the one that works now.
    private suspend fun watchPanelUrls() {
        while (currentCoroutineContext().isActive) {
            Steward.postPanelUrls(lanAddresses().map { "http://$it:${Config.PANEL_PORT}/" })
            delay(5_000L)
        }
    }

    private fun lanAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress.orEmpty() }
            .filter { it.isNotEmpty() }
            .distinct()
    }.getOrDefault(emptyList())

    // Browsers cannot browse mDNS services, so this does nothing for the panel today. It is
    // here to find out whether it registers cleanly on these phones, since a native admin
    // mode would discover the display this way rather than by typing an IP.
    private fun registerNsd() {
        val manager = getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "Dune Companion"
            serviceType = "_http._tcp."
            port = Config.PANEL_PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { Log.i(TAG, "nsd registered: ${info.serviceName}") }
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { Log.w(TAG, "nsd failed: $error") }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onSuccess { nsd = manager; nsdListener = listener }
            .onFailure { Log.w(TAG, "nsd unavailable", it) }
    }

    // ---- Audio -----------------------------------------------------------------------

    private fun enqueue(sequence: AudioSequence) {
        if (audioQueue.trySend(sequence).isFailure) {
            Log.w(TAG, "audio queue rejected: ${sequence.clips.joinToString { it.name }}")
        }
    }

    private suspend fun runAudioWorker() = coroutineScope {
        for (sequence in audioQueue) {
            val job = launch {
                try {
                    sequence.bed?.let(::startBed)
                    playSequence(sequence.clips)
                } catch (c: CancellationException) {
                    Log.w(TAG, "playback cancelled")
                } catch (t: Throwable) {
                    Log.e(TAG, "playback failed", t)
                }
            }
            playbackJob = job
            job.join()
            playbackJob = null
        }
    }

    private fun resetAudio() {
        stopBed()
        while (audioQueue.tryReceive().isSuccess) Unit
        playbackJob?.cancel()
        playbackJob = null
        tts?.stop()
        pendingUtterances.values.toList().forEach { it() }
        pendingUtterances.clear()
    }

    private sealed interface Resolved {
        data class Clip(val spec: ClipSpec, val file: File) : Resolved
        data class Speech(val text: String) : Resolved
        data class Tone(val tone: DuneTone) : Resolved
    }

    private fun resolve(spec: ClipSpec): Resolved? {
        spec.candidates.firstNotNullOfOrNull { clipFile(clipsDir, it) }?.let { return Resolved.Clip(spec, it) }
        duneTone(spec.name)?.let { return Resolved.Tone(it) }
        val text = spec.speech ?: duneSpeechFor(spec.name)
        return text?.let { Resolved.Speech(it) }
    }

    private suspend fun playSequence(specs: List<ClipSpec>) {
        // Adjacent spoken lines become one utterance: "Aisha" then "has the turn." spoken as
        // two would leave an audible gap between a name and its sentence.
        val parts = mutableListOf<Resolved>()
        for (spec in specs) {
            val next = resolve(spec)
            if (next == null) {
                Log.i(TAG, "nothing to play for ${spec.name}")
                continue
            }
            val last = parts.lastOrNull()
            if (next is Resolved.Speech && last is Resolved.Speech) {
                parts[parts.lastIndex] = Resolved.Speech("${last.text} ${next.text}")
            } else {
                parts += next
            }
        }
        for (part in parts) {
            try {
                when (part) {
                    is Resolved.Clip -> playClipFile(part.spec, part.file)
                    is Resolved.Speech -> speak(part.text)
                    is Resolved.Tone -> playTone(part.tone)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "part failed: $part", t)
            }
        }
    }

    private suspend fun speak(text: String) {
        val engine = tts
        if (engine == null || !ttsReady) {
            Log.w(TAG, "tts not ready, dropped: $text")
            return
        }
        Log.d(TAG, "say: $text")
        val id = "u${nextUtteranceId++}"
        try {
            withTimeout(Config.SPEECH_TIMEOUT_MS) {
                suspendCancellableCoroutine<Unit> { cont ->
                    pendingUtterances[id] = { if (cont.isActive) cont.resume(Unit) }
                    val queued = engine.speak(text, TextToSpeech.QUEUE_ADD, null, id)
                    if (queued != TextToSpeech.SUCCESS) pendingUtterances.remove(id)?.invoke()
                    cont.invokeOnCancellation { pendingUtterances.remove(id) }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            Log.e(TAG, "STALLED: speech \"$text\" — abandoning")
        }
    }

    private suspend fun playTone(tone: DuneTone) {
        val (type, ms) = when (tone) {
            DuneTone.TURN_END -> ToneGenerator.TONE_PROP_BEEP to 200
            DuneTone.REVEAL_END -> ToneGenerator.TONE_PROP_ACK to 400
            DuneTone.REJECTED -> ToneGenerator.TONE_PROP_NACK to 500
        }
        val generator = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull() ?: return
        try {
            generator.startTone(type, ms)
            delay(ms + 80L)
        } finally {
            generator.release()
        }
    }

    private fun startBed(spec: ClipSpec) {
        stopBed()
        val file = spec.candidates.firstNotNullOfOrNull { clipFile(clipsDir, it) } ?: return
        runCatching {
            val player = MediaPlayer().apply {
                setAudioAttributes(speechAttributes())
                setDataSource(file.absolutePath)
                prepare()
                setVolume(spec.volume, spec.volume)
            }
            player.setOnCompletionListener { it.release(); if (bedPlayer === it) bedPlayer = null }
            player.setOnErrorListener { m, _, _ -> m.release(); if (bedPlayer === m) bedPlayer = null; true }
            bedPlayer = player
            player.start()
        }.onFailure { Log.w(TAG, "bed failed: ${spec.name}", it) }
    }

    private fun stopBed() {
        val player = bedPlayer ?: return
        bedPlayer = null
        runCatching { player.stop() }
        runCatching { player.release() }
    }

    private suspend fun playClipFile(spec: ClipSpec, file: File) {
        var mp: MediaPlayer? = null
        var enhancer: LoudnessEnhancer? = null
        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(speechAttributes())
                setDataSource(file.absolutePath)
                prepare()
            }
            mp = player
            if (spec.boostMillibels > 0) {
                enhancer = runCatching {
                    LoudnessEnhancer(player.audioSessionId).apply {
                        setTargetGain(spec.boostMillibels)
                        enabled = true
                    }
                }.getOrNull()
            }
            val budget = player.duration.takeIf { it > 0 }?.let { it + Config.CLIP_TIMEOUT_MARGIN_MS }
                ?: Config.CLIP_TIMEOUT_UNKNOWN_MS
            try {
                withTimeout(budget) {
                    suspendCancellableCoroutine<Unit> { cont ->
                        player.setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
                        player.setOnErrorListener { _, _, _ -> if (cont.isActive) cont.resume(Unit); true }
                        player.start()
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                Log.e(TAG, "STALLED: ${spec.name} — abandoning")
            }
        } finally {
            runCatching { enhancer?.release() }
            runCatching { mp?.release() }
        }
    }

    private fun speechAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Steward", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Dune Companion is running")
            .setContentText("Keeping the turn timer, voice and gestures going")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(tap)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                PendingIntent.getService(
                    this, 1,
                    Intent(this, StewardService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "steward"
        private const val TAG = "DuneAudio"
        const val ACTION_STOP = "io.github.deepakgsingh.dunecompanion.action.STOP"
    }
}
