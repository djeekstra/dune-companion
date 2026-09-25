package io.github.deepakgsingh.dunecompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GesturesTest {
    // 50 Hz, as SENSOR_DELAY_GAME delivers.
    private val dtS = 0.02f
    private val stepMs = 20L

    /** Feeds a constant rotation rate for [ms] and returns the tracker, the clock, and fire count. */
    private fun run(
        start: SpinTracker,
        fromMs: Long,
        rateRadS: Float,
        ms: Long,
        flat: Boolean = true,
    ): Triple<SpinTracker, Long, Int> {
        var tracker = start
        var now = fromMs
        var fires = 0
        repeat((ms / stepMs).toInt()) {
            now += stepMs
            val (next, fired) = tracker.observe(rateRadS, dtS, flat, now)
            tracker = next
            if (fired) fires++
        }
        return Triple(tracker, now, fires)
    }

    // Lying still on the table long enough for a spin to count.
    private fun rested() = SpinTracker(restingSinceMs = -10_000L)

    // 2π rad/s is one turn a second; a comfortable flick of a phone on a table.
    private val oneTurnPerSecond = (2 * Math.PI).toFloat()

    @Test
    fun aFullSpinFiresOnceInEitherDirection() {
        assertEquals(1, run(rested(), 1_000L, oneTurnPerSecond, 1_200L).third)
        assertEquals(1, run(rested(), 1_000L, -oneTurnPerSecond, 1_200L).third)
    }

    @Test
    fun aLazySpinStillCounts() {
        // ~100 degrees a second: a slow push that takes over three seconds to go round.
        assertEquals(1, run(rested(), 1_000L, 1.75f, 3_600L).third)
    }

    @Test
    fun halfATurnDoesNot() {
        val (tracker, now, fires) = run(rested(), 1_000L, oneTurnPerSecond, 500L)
        assertEquals(0, fires)
        // ...and once it has sat still past a regrip, the half turn is forgotten.
        val (after, _, laterFires) = run(tracker, now, 0f, Config.SPIN_REGRIP_MS + 200L)
        assertEquals(0, laterFires)
        assertEquals(0f, after.degrees)
    }

    @Test
    fun twoHalfTurnsTheSameWayWithARegripAddUp() {
        // Pixel 4a, 2026-09-25: halves of ~180 degrees, 1.5-2 s apart.
        val (a, t1, _) = run(rested(), 1_000L, oneTurnPerSecond, 500L)
        val (b, t2, _) = run(a, t1, 0f, 2_000L)
        val (_, _, fires) = run(b, t2, oneTurnPerSecond, 500L)
        assertEquals(1, fires)
    }

    @Test
    fun aHalfTurnAndAHalfTurnBackDoNotAddUp() {
        val (a, t1, _) = run(rested(), 1_000L, oneTurnPerSecond, 500L)
        val (b, t2, _) = run(a, t1, 0f, 1_000L)
        val (_, _, fires) = run(b, t2, -oneTurnPerSecond, 500L)
        assertEquals(0, fires)
    }

    @Test
    fun halvesTooFarApartDoNotAddUp() {
        val (a, t1, _) = run(rested(), 1_000L, oneTurnPerSecond, 500L)
        val (b, t2, _) = run(a, t1, 0f, Config.SPIN_REGRIP_MS + 500L)
        val (_, _, fires) = run(b, t2, oneTurnPerSecond, 500L)
        assertEquals(0, fires)
    }

    @Test
    fun aPhoneSetDownWithATwistThatCoastsRoundDoesNotEndATurn() {
        // Pixel 4a, 2026-09-25: switched off in the hand, set down with a slight turn, and it
        // coasted 300 degrees inside a second of landing.
        val (landed, t1, _) = run(SpinTracker(), 1_000L, 0f, 300L, flat = false)
        val (after, t2, fires) = run(landed, t1, 13f, 1_000L)
        assertEquals(0, fires)
        assertTrue("the refusal is recorded for the log", after.lastIgnoredAtMs > 0L)
        // Once it has come to rest, a real spin still works.
        val (still, t3, _) = run(after, t2, 0f, Config.SPIN_REST_BEFORE_MS + 100L)
        assertEquals(1, run(still, t3, oneTurnPerSecond, 1_200L).third)
    }

    @Test
    fun aLandingFlickStillSilencesShakesEvenThoughItCannotFire() {
        val (_, t1, _) = run(SpinTracker(), 1_000L, 0f, 300L, flat = false)
        val (spinning, t2, _) = run(SpinTracker(), t1, 5f, 300L)
        assertTrue(spinning.ownsMotion(t2))
    }

    @Test
    fun spinningWhileHeldUpDoesNothing() {
        assertEquals(0, run(rested(), 1_000L, oneTurnPerSecond, 2_000L, flat = false).third)
    }

    @Test
    fun gyroBiasNeverAddsUp() {
        // A resting gyro reads a few hundredths of a rad/s; an hour of it must not become a turn.
        val (tracker, _, fires) = run(rested(), 1_000L, 0.03f, 3_600_000L)
        assertEquals(0, fires)
        assertEquals(0f, tracker.degrees)
    }

    @Test
    fun aBackAndForthWiggleCancelsOut() {
        var tracker = SpinTracker()
        var now = 1_000L
        var fires = 0
        repeat(6) { i ->
            val rate = if (i % 2 == 0) oneTurnPerSecond else -oneTurnPerSecond
            val r = run(tracker, now, rate, 400L)
            tracker = r.first; now = r.second; fires += r.third
        }
        assertEquals(0, fires)
    }

    @Test
    fun slowFidgetingOverManySecondsIsNotASpin() {
        // Just above the rate threshold, never stalling, but far too slow to finish in the window.
        assertEquals(0, run(rested(), 1_000L, 0.7f, 20_000L).third)
    }

    @Test
    fun aSecondSpinInsideTheCooldownIsSwallowed() {
        val (a, t1, first) = run(rested(), 1_000L, oneTurnPerSecond, 1_200L)
        assertEquals(1, first)
        val (_, _, second) = run(a, t1, oneTurnPerSecond, 1_000L)
        assertEquals(0, second)
    }

    @Test
    fun twoDeliberateSpinsAFewSecondsApartBothCount() {
        val (a, t1, first) = run(rested(), 1_000L, oneTurnPerSecond, 1_200L)
        val (b, t2, _) = run(a, t1, 0f, Config.SPIN_COOLDOWN_MS + Config.SPIN_REST_BEFORE_MS)
        val (_, _, second) = run(b, t2, oneTurnPerSecond, 1_200L)
        assertEquals(1, first)
        assertEquals(1, second)
    }

    @Test
    fun settingThePhoneDownAfterAShakeCannotSpin() {
        val shaken = SpinTracker().afterShake(1_000L)
        // Set down and swivelled straight a full turn's worth, inside the grace window.
        assertEquals(0, run(shaken, 1_000L, oneTurnPerSecond, 2_000L).third)
        // After the window, and a moment lying still, a spin works again.
        val (settled, t, _) = run(shaken, 1_000L, 0f, Config.DUNE_SHAKE_SPIN_GRACE_MS + Config.SPIN_REST_BEFORE_MS)
        assertEquals(1, run(settled, t, oneTurnPerSecond, 1_200L).third)
    }

    @Test
    fun onlyFaceUpAndLevelCountsAsFlat() {
        assertTrue(isSpinFlat(9.72f))
        assertFalse("face down", isSpinFlat(-9.8f))
        assertFalse("held upright", isSpinFlat(0.3f))
        assertFalse("tilted to read", isSpinFlat(7.0f))
    }

    @Test
    fun centripetalPushFromABriskSpinStillCountsAsFlat() {
        // Pixel 4a, 2026-09-25: two turns a second puts ~8 m/s² in the plane of the screen.
        // An in-plane bound on gravity read that as "not flat" and reset every brisk spin.
        var gravity = GravityFilter()
        repeat(100) { gravity = gravity.observe(7.9f, 0.4f, 9.72f) }
        assertTrue(isSpinFlat(gravity.z))
    }

    @Test
    fun aShakeInTheHandNeverLooksLikeASpin() {
        // Pixel 4a, 2026-09-25: shaken near flat, the phone twists back and forth at ±3 rad/s.
        // That must never silence the shake detector, or shaking to reveal stops working.
        var tracker = SpinTracker()
        var now = 1_000L
        repeat(10) { i ->
            val rate = if (i % 2 == 0) 5f else -5f
            repeat(5) {
                now += stepMs
                tracker = tracker.observe(rate, dtS, flat = true, nowMs = now).first
                assertFalse("shake at ${tracker.degrees}° looked like a spin", tracker.ownsMotion(now))
            }
        }
    }

    @Test
    fun aSpinUnderWayOrCoastingSilencesShakes() {
        val (partway, t1, _) = run(rested(), 1_000L, oneTurnPerSecond, 400L)
        assertTrue("144° into a spin", partway.ownsMotion(t1))
        val (fired, t2, fires) = run(rested(), 1_000L, oneTurnPerSecond, 1_000L)
        assertEquals(1, fires)
        assertTrue("coasting just after firing", fired.ownsMotion(t2))
        assertFalse("well after", fired.ownsMotion(t2 + Config.SPIN_SHAKE_QUIET_MS + 100L))
    }

    @Test
    fun aShakeThatTurnsOutToBeASpinsFlickIsCancelled() {
        // The third jolt lands at the flick, before the spin has gone round far.
        val detectedAt = 1_000L
        // A lazy flick, ~3 rad/s, the slowest that produced jolts on the Pixel 4a.
        val (spinning, now, _) = run(rested(), detectedAt, 3f, 400L)
        assertEquals(ShakeVerdict.CANCEL, resolvePendingShake(detectedAt, spinning, now))
    }

    @Test
    fun aRealShakeRevealsOnceTheConfirmTimeHasPassed() {
        var tracker = SpinTracker()
        var now = 1_000L
        val detectedAt = now
        var verdict = ShakeVerdict.WAIT
        while (verdict == ShakeVerdict.WAIT) {
            now += stepMs
            // Hand shake: twisting back and forth every 100 ms.
            val rate = if ((now / 100L) % 2L == 0L) 5f else -5f
            tracker = tracker.observe(rate, dtS, flat = true, nowMs = now).first
            verdict = resolvePendingShake(detectedAt, tracker, now)
        }
        assertEquals(ShakeVerdict.REVEAL, verdict)
        assertEquals(Config.SHAKE_CONFIRM_MS, now - detectedAt)
    }

    private fun taps(vararg atMs: Long): Int {
        var counter = TapCounter()
        var fires = 0
        atMs.forEach { t -> val (next, fired) = counter.observe(t); counter = next; if (fired) fires++ }
        return fires
    }

    @Test
    fun threeQuickTapsEndATurnAndTwoDoNot() {
        assertEquals(1, taps(1_000L, 1_250L, 1_500L))
        assertEquals(0, taps(1_000L, 1_250L))
    }

    @Test
    fun tapsTooFarApartStartOver() {
        assertEquals(0, taps(1_000L, 1_250L, 2_000L))
        // ...and the late tap is the first of a fresh triple.
        assertEquals(1, taps(1_000L, 1_250L, 2_000L, 2_200L, 2_400L))
    }

    private fun shakeWith(magnitude: Float): Boolean {
        var tracker = ShakeTracker()
        var fired = false
        listOf(1_000L, 1_200L, 1_400L).forEach { t ->
            val (next, f) = tracker.observe(magnitude, 0f, 0f, t)
            tracker = next; fired = fired || f
        }
        return fired
    }

    @Test
    fun threeRealJoltsInsideASecondIsAShake() {
        // Pixel 4a, 2026-09-25: a hand shake measured 46-62 m/s².
        assertTrue(shakeWith(46f))
    }

    @Test
    fun hardTiltingIsNotAShake() {
        // The same session's hardest tilting peaked at 12.9 m/s², even three times running.
        assertFalse(shakeWith(12.9f))
    }
}

class DuneSpeechTest {
    @Test
    fun fixedCuesHaveSpokenLines() {
        assertEquals("Combat!", duneSpeechFor("dune_phase_combat.wav"))
        assertEquals("has the turn.", duneSpeechFor(DUNE_PHRASE_TURN))
        assertEquals("Round 7.", duneSpeechFor("dune_conflict_07.wav"))
        assertEquals("Blue wins!", duneSpeechFor("dune_winner_blue.wav"))
        assertEquals("Red takes the mentat.", duneSpeechFor("dune_mentat_red.wav"))
        assertEquals("2 hours played.", duneSpeechFor("dune_game_hour_2.wav"))
        assertEquals("Timer paused.", duneSpeechFor("dune_timer_paused"))
    }

    @Test
    fun soundEffectsAreTonesNotSpeech() {
        assertEquals(DuneTone.TURN_END, duneTone(DUNE_TURN_END_CUE))
        assertEquals(DuneTone.REJECTED, duneTone(DUNE_TURN_REJECTED_CUE))
        assertEquals(null, duneSpeechFor(DUNE_REVEAL_END_CUE))
    }

    @Test
    fun aNamedSeatSpeaksWhatTheHostTyped() {
        val session = (startDuneSession(
            listOf(DunePlayerColor.BLUE, DunePlayerColor.RED, DunePlayerColor.GREEN),
            DunePlayerColor.BLUE, 0L,
            mapOf(DunePlayerColor.RED to listOf("Mary Jane")),
        ) as DuneActionResult.Success).session
        // Typed text, not the file slug: "maryjane" would be read as one word.
        assertEquals("Mary Jane", duneNameClip(session, DunePlayerColor.RED).speech)
        assertEquals("blue", duneNameClip(session, DunePlayerColor.BLUE).speech)
    }

    @Test
    fun everyCueTheGameCanEmitHasSomethingToSay() {
        val names = buildList {
            DunePhase.entries.forEach { phase -> dunePhaseCueFilename(phase)?.let(::add) }
            DuneTimerMark.entries.forEach { add(it.clip) }
            DunePlayerColor.entries.forEach {
                add(duneWinnerCueFilename(it))
                add("dune_third_agent_${it.wireName}.wav")
                add("dune_high_council_${it.wireName}.wav")
            }
            (1..10).forEach { add("dune_conflict_%02d.wav".format(it)) }
            (1..6).forEach { add(duneHourlyCueFilename(it)) }
            addAll(listOf(DUNE_PHRASE_TURN, DUNE_PHRASE_ALREADY_REVEALED, DUNE_PHRASE_FIRST_PLAYER,
                DUNE_PHRASE_TURN_RESTORED, "dune_timer_paused", "dune_game_resumed"))
        }
        val silent = names.filter { duneSpeechFor(it) == null }
        assertEquals(emptyList<String>(), silent)
    }
}
