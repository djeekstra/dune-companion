package io.github.deepakgsingh.dunecompanion

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class DuneEffectKind { AGENT_COMPLETE, REVEAL_COMPLETE }

data class DuneActionEffectEvent(val id: String, val kind: DuneEffectKind)

/** What the gesture watcher is seeing, for the on-screen debug overlay and /debug/state. */
data class GestureDebug(
    val armed: Boolean = false,
    val sensors: List<String> = emptyList(),
    val gravity: Triple<Float, Float, Float> = Triple(0f, 0f, 0f),
    val flat: Boolean = false,
    val spinDegrees: Float = 0f,
    val shakeJolts: Int = 0,
    val spins: Int = 0,
    val spinsRejected: Int = 0,
    val shakes: Int = 0,
    val taps: Int = 0,
    // Letters drawn on the display: how many claimed a space, and what the last attempt read as.
    val glyphs: Int = 0,
    val lastGlyph: String = "",
)

/**
 * Shared state between StewardService (writer) and the display (reader).
 */
object Steward {
    private const val TAG = "DuneAudio"

    private val _dune = MutableStateFlow(DuneSession())
    val dune: StateFlow<DuneSession> = _dune.asStateFlow()
    fun postDune(session: DuneSession) { _dune.value = session }

    private val _audioRequest = MutableSharedFlow<AudioSequence>(extraBufferCapacity = 64)
    val audioRequest: SharedFlow<AudioSequence> = _audioRequest.asSharedFlow()
    fun emitAudioClips(clips: List<ClipSpec>, bed: ClipSpec? = null) {
        if (clips.isEmpty()) return
        if (!_audioRequest.tryEmit(AudioSequence(clips, bed))) {
            Log.w(TAG, "dropped ${clips.size} clip(s): ${clips.joinToString { it.name }}")
        }
    }

    private val _audioReset = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val audioReset: SharedFlow<Unit> = _audioReset.asSharedFlow()
    fun requestAudioReset() { _audioReset.tryEmit(Unit) }

    private val _duneEffects = MutableSharedFlow<DuneActionEffectEvent>(extraBufferCapacity = 16)
    val duneEffects: SharedFlow<DuneActionEffectEvent> = _duneEffects.asSharedFlow()
    fun emitDuneEffect(event: DuneActionEffectEvent) { _duneEffects.tryEmit(event) }

    // Where the admin panel can be reached, for the display to show. Empty until the server
    // is up and the phone has a LAN address.
    private val _panelUrls = MutableStateFlow<List<String>>(emptyList())
    val panelUrls: StateFlow<List<String>> = _panelUrls.asStateFlow()
    fun postPanelUrls(urls: List<String>) { _panelUrls.value = urls }

    private val _gestures = MutableStateFlow(GestureDebug())
    val gestures: StateFlow<GestureDebug> = _gestures.asStateFlow()
    fun updateGestures(transform: (GestureDebug) -> GestureDebug) { _gestures.update(transform) }

    // Closing the app from the display or the notification. The activity finishes itself on
    // this; the service stops itself separately, so it also works with no activity open.
    private val _quit = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val quit: SharedFlow<Unit> = _quit.asSharedFlow()
    fun requestQuit() { _quit.tryEmit(Unit) }

    // An on-screen triple tap, the fallback for a spin that will not register.
    private val _tapTurnEnd = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val tapTurnEnd: SharedFlow<Unit> = _tapTurnEnd.asSharedFlow()
    fun requestTapTurnEnd() { _tapTurnEnd.tryEmit(Unit) }

    // A letter recognised on the display, to claim that space for whoever has the turn.
    private val _glyphClaims = MutableSharedFlow<Glyph>(extraBufferCapacity = 4)
    val glyphClaims: SharedFlow<Glyph> = _glyphClaims.asSharedFlow()
    fun requestGlyphClaim(glyph: Glyph) { _glyphClaims.tryEmit(glyph) }
}
