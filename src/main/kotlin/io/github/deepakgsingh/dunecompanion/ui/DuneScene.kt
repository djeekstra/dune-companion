package io.github.deepakgsingh.dunecompanion.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deepakgsingh.dunecompanion.Config
import io.github.deepakgsingh.dunecompanion.DunePhase
import io.github.deepakgsingh.dunecompanion.DuneSession
import io.github.deepakgsingh.dunecompanion.duneGameElapsedMs
import kotlinx.coroutines.delay

@Composable
fun DuneScene(session: DuneSession) {
    Box(Modifier.fillMaxSize().background(Palette.Ink)) {
        Crossfade(
            targetState = session.showingWinner,
            animationSpec = tween(600),
            label = "duneState",
        ) { winner ->
            when {
                winner -> DuneWinnerCard(session)
                session.phase == DunePhase.TURNS -> DuneRoundCard(session)
                else -> DunePhaseCard(session)
            }
        }
    }
}

@Composable
private fun DunePhaseCard(session: DuneSession) {
    val transition = rememberInfiniteTransition(label = "dunePhase")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(700, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "dunePhaseAlpha",
    )
    Box(
        Modifier.fillMaxSize().background(Palette.Ink),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            session.phase.displayName,
            color = Palette.Fire.copy(alpha = alpha),
            fontSize = 56.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            letterSpacing = 3.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DuneRoundCard(session: DuneSession) {
    var now by remember(session.turnStartedAtMs, session.phaseStartedAtMs) {
        mutableLongStateOf(System.currentTimeMillis())
    }
    LaunchedEffect(session.turnStartedAtMs, session.phaseStartedAtMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val hasTurn = session.phase == DunePhase.TURNS && session.currentPlayer != null
    val effectiveNow = if (session.paused) session.pausedAtMs ?: now else now
    val elapsedMs = if (hasTurn) (effectiveNow - session.turnStartedAtMs).coerceAtLeast(0L) else 0L
    val transition = rememberInfiniteTransition(label = "duneTimer")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(700, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "duneTimerAlpha",
    )
    val timerAlpha = if (!session.paused && elapsedMs >= Config.DUNE_TIMER_PULSE_MS) pulseAlpha else 1f

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 25.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HeaderRule("ROUND ${session.round}", "CONFLICT ${session.conflictTier.roman}")
        Spacer(Modifier.height(32.dp))
        Text(session.phase.displayName, color = Palette.Ember, fontSize = 25.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        Spacer(Modifier.height(34.dp))
        val playerLabel = when {
            session.currentPlayer != null -> session.seatName(session.currentPlayer)
                ?.let { "$it · ${session.currentPlayer.displayName}" }
                ?: session.currentPlayer.displayName
            session.turnsComplete && session.phase == io.github.deepakgsingh.dunecompanion.DunePhase.TURNS -> "TURNS COMPLETE"
            else -> session.phase.displayName
        }
        Text(
            playerLabel,
            color = Palette.Ember,
            // A seat shared by two people is roughly twice as wide as a single name, and 30sp
            // wraps it to three lines on a 480px display — enough to push the timer off the
            // bottom. Stepping down keeps a shared seat to two.
            fontSize = when {
                session.currentPlayer == null -> 20.sp
                playerLabel.length > 18 -> 20.sp
                else -> 30.sp
            },
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            letterSpacing = 3.sp,
        )
        // No agent count here. The phone is the input: a player spins it when they
        // have taken a turn, so what the table needs from this card is whose turn it is and how
        // long they have had it. A number that gates nothing, and that the board can contradict
        // at any moment, was only ever something else to disbelieve.
        if (hasTurn) {
            Spacer(Modifier.height(28.dp))
            Text(
                formatDuration(elapsedMs),
                color = Palette.Fire.copy(alpha = timerAlpha),
                fontSize = 84.sp,
                lineHeight = 90.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
            )
            if (session.paused) {
                Text(
                    "GAME TIMER PAUSED",
                    color = Palette.Fire,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                )
            } else if (elapsedMs >= Config.DUNE_TIMER_TEN_MINUTES_MS) {
                Text(
                    "TEN MINUTES ELAPSED",
                    color = Palette.Fire.copy(alpha = timerAlpha),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            // Reveal state, not a tally: the one thing about a player that is certain, that the
            // round actually turns on, and that nobody has to take the app's word for.
            session.playerColors.joinToString("  ") { color ->
                val mark = if (color in session.revealedPlayers) "✓" else "–"
                "${color.displayName.first()}·$mark"
            },
            color = Palette.Ash,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
    }
}

@Composable
private fun DuneWinnerCard(session: DuneSession) {
    val duration = duneGameElapsedMs(session, System.currentTimeMillis())
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 25.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        HeaderRule("DUNE · IMPERIUM", "GAME COMPLETE")
        Spacer(Modifier.weight(1f))
        session.winner?.let { winner ->
            session.seatName(winner)?.let { name ->
                Text(
                    name,
                    color = Palette.Ash,
                    fontSize = 22.sp,
                    textAlign = TextAlign.Center,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        Text(
            session.winner?.displayName ?: "—",
            color = Palette.Fire,
            fontSize = 60.sp,
            lineHeight = 66.sp,
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
        )
        Text(
            "WINS",
            color = Palette.Ember,
            fontSize = 28.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            letterSpacing = 5.sp,
        )
        Spacer(Modifier.height(45.dp))
        Text(
            "${session.round} ROUNDS · ${formatDuration(duration)}",
            color = Palette.Ash,
            fontSize = 26.sp,
            lineHeight = 31.sp,
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun HeaderRule(left: String, right: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            left,
            color = Palette.Fire,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Palette.Ember))
        Spacer(Modifier.width(12.dp))
        Text(
            right,
            color = Palette.Fire,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}
