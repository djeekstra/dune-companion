package io.github.deepakgsingh.dunecompanion.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import io.github.deepakgsingh.dunecompanion.DuneAutoAdvance
import io.github.deepakgsingh.dunecompanion.DunePhase
import io.github.deepakgsingh.dunecompanion.duneAutoAdvance
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
    Column(
        Modifier.fillMaxSize().background(Palette.Ink).padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
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
        Spacer(Modifier.height(40.dp))
        val auto = duneAutoAdvance(session)
        if (auto != null) AutoAdvanceCountdown(auto)
        else waitingFor(session)?.let { PhaseHint(it) }
    }
}

// Collect your rewards, Makers and Recall all move on by themselves, and a table that doesn't
// know that will sit waiting for someone to press something. The draining bar says "this is
// about to change, and to what" without anyone having to read the panel.
@Composable
private fun AutoAdvanceCountdown(auto: DuneAutoAdvance) {
    var now by remember(auto) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(auto) {
        while (true) {
            now = System.currentTimeMillis()
            delay(50L)
        }
    }
    val remainingMs = (auto.dueAtMs - now).coerceIn(0L, auto.durationMs)
    val fraction = if (auto.durationMs <= 0L) 0f else remainingMs.toFloat() / auto.durationMs
    PhaseHint("${auto.next} IN ${(remainingMs + 999L) / 1_000L}")
    Spacer(Modifier.height(14.dp))
    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Palette.Char)) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Palette.Ember))
    }
}

/** What the table has to do before the phase can move on, when it will not move by itself. */
private fun waitingFor(session: DuneSession): String? = when {
    session.checkpointRequired -> "ENTER SCORES ON THE PANEL"
    session.phase == DunePhase.COMBAT -> "SHAKE WHEN COMBAT IS RESOLVED"
    else -> null
}

@Composable
private fun PhaseHint(text: String) {
    Text(
        text,
        color = Palette.Ember,
        fontSize = 18.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Black,
        letterSpacing = 2.sp,
        textAlign = TextAlign.Center,
    )
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
                    // Resuming is a panel job, and a table reopening the app after a break
                    // would otherwise stare at a frozen clock with no idea why.
                    "GAME TIMER PAUSED\nRESUME ON THE PANEL",
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
        RevealRow(session)
    }
}

/**
 * Who has revealed: each seat's initial in a circle, filled once they have.
 *
 * Reveal state, not a tally: the one thing about a player that is certain, that the round
 * actually turns on, and that nobody has to take the app's word for. Labelled, because a row of
 * letters and dashes meant nothing to anyone who hadn't been told.
 */
@Composable
private fun RevealRow(session: DuneSession) {
    val revealed = session.playerColors.count { it in session.revealedPlayers }
    Text(
        "REVEALED $revealed OF ${session.playerColors.size}",
        color = Palette.Ash,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
    )
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        session.playerColors.forEach { color ->
            val revealed = color in session.revealedPlayers
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .border(2.dp, Palette.Fire, CircleShape)
                    .background(if (revealed) Palette.Fire else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    color.displayName.first().toString(),
                    color = if (revealed) Palette.Ink else Palette.Fire,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                )
            }
        }
    }
    Spacer(Modifier.height(24.dp))
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
