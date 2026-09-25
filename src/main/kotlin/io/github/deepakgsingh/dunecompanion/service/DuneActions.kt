package io.github.deepakgsingh.dunecompanion.service

import io.github.deepakgsingh.dunecompanion.ClipSpec
import io.github.deepakgsingh.dunecompanion.DUNE_CUE_BOOST
import io.github.deepakgsingh.dunecompanion.DUNE_REVEAL_END_CUE
import io.github.deepakgsingh.dunecompanion.DUNE_TURN_END_CUE
import io.github.deepakgsingh.dunecompanion.DuneActionEffectEvent
import io.github.deepakgsingh.dunecompanion.DuneActionResult
import io.github.deepakgsingh.dunecompanion.DuneEffectKind
import io.github.deepakgsingh.dunecompanion.DuneSession
import io.github.deepakgsingh.dunecompanion.Steward
import io.github.deepakgsingh.dunecompanion.duneAlreadyRevealedClips
import io.github.deepakgsingh.dunecompanion.duneNextTurnClips
import io.github.deepakgsingh.dunecompanion.duneSkippedRevealedPlayers
import io.github.deepakgsingh.dunecompanion.duneTransitionBed
import io.github.deepakgsingh.dunecompanion.duneTransitionClips

// Every Dune action is a read-modify-write of Steward.dune, and the panel, the spin, the shake
// and the on-screen tap can all land at once. The whole read-through-post runs under one lock.
private val duneActionLock = Any()

/**
 * Applies a Dune action and emits its cues. Shared by the panel and the gesture watcher so cue
 * ordering is identical no matter which one ended the turn. Returns null when refused.
 */
internal fun applyDuneResult(
    result: DuneActionResult,
    playCue: Boolean = true,
    previous: DuneSession = Steward.dune.value,
    turnEndCue: String? = null,
    cues: List<ClipSpec> = emptyList(),
): DuneSession? = when (result) {
    is DuneActionResult.Error -> null
    is DuneActionResult.Success -> {
        Steward.postDune(result.session)
        if (turnEndCue == DUNE_TURN_END_CUE || turnEndCue == DUNE_REVEAL_END_CUE) {
            result.session.turnRecords.lastOrNull()?.let { record ->
                Steward.emitDuneEffect(
                    DuneActionEffectEvent("dune-turn-${record.id}", DuneEffectKind.REVEAL_COMPLETE)
                )
            }
        }
        val toPlay = buildList {
            turnEndCue?.let { add(ClipSpec(it, DUNE_CUE_BOOST)) }
            val skipped = duneSkippedRevealedPlayers(previous, result.session)
            skipped.forEach { addAll(duneAlreadyRevealedClips(result.session, it)) }
            // Announce the handover on every turn end: a phone lying on the table is easy to
            // lose sight of, and naming who is up is the whole point of the steward.
            if (turnEndCue != null || skipped.isNotEmpty()) {
                result.session.currentPlayer
                    ?.takeIf { it != previous.currentPlayer || skipped.isNotEmpty() }
                    ?.let { addAll(duneNextTurnClips(result.session, it)) }
            }
            addAll(cues)
            if (playCue) addAll(duneTransitionClips(previous, result.session))
        }
        Steward.emitAudioClips(toPlay, duneTransitionBed(previous, result.session))
        result.session
    }
}

/** Runs one read-modify-write of the Dune session to the exclusion of all others. */
internal fun <T> withDuneLock(block: () -> T): T = synchronized(duneActionLock, block)
