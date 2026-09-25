package io.github.deepakgsingh.dunecompanion

import io.github.deepakgsingh.dunecompanion.Config.DUNE_SHAKE_ACCEL_MS2
import io.github.deepakgsingh.dunecompanion.Config.DUNE_SHAKE_COOLDOWN_MS
import io.github.deepakgsingh.dunecompanion.Config.DUNE_SHAKE_COUNT
import io.github.deepakgsingh.dunecompanion.Config.DUNE_SHAKE_MIN_GAP_MS
import io.github.deepakgsingh.dunecompanion.Config.DUNE_SHAKE_SPIN_GRACE_MS
import io.github.deepakgsingh.dunecompanion.Config.DUNE_SHAKE_WINDOW_MS
import io.github.deepakgsingh.dunecompanion.Config.DUNE_TILT_GRAVITY_ALPHA
import io.github.deepakgsingh.dunecompanion.Config.SHAKE_CONFIRM_MS
import io.github.deepakgsingh.dunecompanion.Config.TRIPLE_TAP_GAP_MS
import io.github.deepakgsingh.dunecompanion.Config.SPIN_COOLDOWN_MS
import io.github.deepakgsingh.dunecompanion.Config.SPIN_FIRE_DEGREES
import io.github.deepakgsingh.dunecompanion.Config.SPIN_FLAT_MIN_Z
import io.github.deepakgsingh.dunecompanion.Config.SPIN_MIN_RATE_RAD_S
import io.github.deepakgsingh.dunecompanion.Config.SPIN_OWNS_MOTION_DEGREES
import io.github.deepakgsingh.dunecompanion.Config.SPIN_SHAKE_QUIET_MS
import io.github.deepakgsingh.dunecompanion.Config.SPIN_DIRECTION_PAUSE_MS
import io.github.deepakgsingh.dunecompanion.Config.SPIN_REGRIP_MS
import io.github.deepakgsingh.dunecompanion.Config.SPIN_REST_BEFORE_MS
import io.github.deepakgsingh.dunecompanion.Config.SPIN_WINDOW_MS
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Splits a raw accelerometer stream into its gravity and applied-force halves. Used for
 * "is it lying flat?", and for shakes on phones whose linear-acceleration sensor is missing or
 * reports only zeros (some do: they register fine and deliver 0,0,0 forever).
 */
data class GravityFilter(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val primed: Boolean = false,
) {
    /** Applied force with gravity removed — the equivalent of TYPE_LINEAR_ACCELERATION. */
    fun linear(rawX: Float, rawY: Float, rawZ: Float): Triple<Float, Float, Float> =
        Triple(rawX - x, rawY - y, rawZ - z)
}

fun GravityFilter.observe(rawX: Float, rawY: Float, rawZ: Float): GravityFilter {
    if (!primed) return GravityFilter(rawX, rawY, rawZ, primed = true)
    val a = DUNE_TILT_GRAVITY_ALPHA
    return GravityFilter(
        x = a * x + (1 - a) * rawX,
        y = a * y + (1 - a) * rawY,
        z = a * z + (1 - a) * rawZ,
        primed = true,
    )
}

/**
 * Lying on its back, screen up — the only pose a spin counts in.
 *
 * Decided from the screen-normal component of gravity alone. Bounding the in-plane component
 * too looks natural, but a spinning phone breaks it: the accelerometer is off the axis
 * of rotation, so a spin adds a centripetal push in the plane of the screen (~2 m/s² at one
 * turn a second, ~8 at two) that the gravity estimate absorbs. Measured on a Pixel 4a
 * (2026-09-25) that reset every brisk spin partway round. The push is entirely in-plane, so z
 * is untouched by it — while lifting the phone to read it tilts z away well past this bound.
 *
 * Face-up specifically: spinning a phone face down would hide the screen that says whose turn
 * it now is, so it is not a gesture anyone should learn.
 */
fun isSpinFlat(gravityZ: Float): Boolean = gravityZ >= SPIN_FLAT_MIN_Z

/**
 * Accumulates rotation about the screen's normal while the phone lies face up, and fires once
 * it adds up to a full turn.
 *
 * The accumulation is signed, so either direction counts but a back-and-forth
 * wiggle cancels itself out rather than adding up.
 */
data class SpinTracker(
    val degrees: Float = 0f,
    val startedAtMs: Long = 0L,
    val lastMovingAtMs: Long = 0L,
    val lastFiredAtMs: Long = 0L,
    val suppressedUntilMs: Long = 0L,
    // Since when the phone has been lying still, or null while it is moving or held.
    val restingSinceMs: Long? = null,
    // Whether the spin under way began from a phone that had been lying still.
    val startedFromRest: Boolean = false,
    // When a full turn was last refused for not starting from rest; for logging.
    val lastIgnoredAtMs: Long = 0L,
) {
    private fun cleared() = SpinTracker(
        lastFiredAtMs = lastFiredAtMs,
        suppressedUntilMs = suppressedUntilMs,
        lastIgnoredAtMs = lastIgnoredAtMs,
    )

    /**
     * Feeds one gyroscope sample: [zRateRadS] is rotation about the screen normal, [dtS] the
     * time since the previous sample, [flat] whether gravity currently says face-up-flat.
     * Returns the updated tracker and whether this sample completes a spin.
     */
    fun observe(zRateRadS: Float, dtS: Float, flat: Boolean, nowMs: Long): Pair<SpinTracker, Boolean> {
        // Picking the phone up is the most common thing that happens to it, and rotating it
        // while lifting it to read is natural. None of that is a spin.
        if (!flat || nowMs < suppressedUntilMs) return cleared() to false
        if (lastFiredAtMs != 0L && nowMs - lastFiredAtMs < SPIN_COOLDOWN_MS) return cleared() to false

        // A pause is not the end of a spin: turning a phone round in two half-turns, letting go
        // to regrip between them, is as natural as one flick (Pixel 4a, 2026-09-25: the gap
        // between halves ran 1.5-2 s). Progress is only dropped once the pause outlasts a regrip.
        if (abs(zRateRadS) < SPIN_MIN_RATE_RAD_S) {
            val abandoned = degrees != 0f && nowMs - lastMovingAtMs > SPIN_REGRIP_MS
            return (if (abandoned) cleared().copy(restingSinceMs = lastMovingAtMs)
                    else copy(restingSinceMs = restingSinceMs ?: nowMs)) to false
        }

        // Start over from this sample when the spin has run too long (slow fidgeting, not a
        // spin), or when it resumes the *other* way after a pause — that is a new attempt, or
        // turning the phone back, and must not be netted against the first.
        val resumedBackwards = degrees != 0f && (degrees > 0f) != (zRateRadS > 0f) &&
            nowMs - lastMovingAtMs > SPIN_DIRECTION_PAUSE_MS
        val rested = restingSinceMs?.let { nowMs - it >= SPIN_REST_BEFORE_MS } == true
        // A turn that did not start from rest, then came to rest, is over: whatever follows is
        // a new, deliberate spin, not the second half of a coast.
        val fresh = degrees == 0f || nowMs - startedAtMs > SPIN_WINDOW_MS || resumedBackwards ||
            (rested && !startedFromRest)
        // A deliberate spin starts from a phone lying still on the table. Setting it down with
        // a twist can send it coasting a full turn (Pixel 4a, 2026-09-25: 300 degrees inside a
        // second of landing, after the screen was switched off), and that has to be ignored.
        // The turn is still tracked — ownsMotion needs it to keep the landing flick from
        // reading as a shake — it just cannot fire.
        val base = if (fresh) {
            cleared().copy(startedAtMs = nowMs, startedFromRest = rested)
        } else this
        val total = base.degrees + Math.toDegrees((zRateRadS * dtS).toDouble()).toFloat()

        if (abs(total) >= SPIN_FIRE_DEGREES) {
            if (!base.startedFromRest) {
                return cleared().copy(lastIgnoredAtMs = nowMs) to false
            }
            return SpinTracker(lastFiredAtMs = nowMs, suppressedUntilMs = suppressedUntilMs,
                lastIgnoredAtMs = lastIgnoredAtMs) to true
        }
        return base.copy(degrees = total, lastMovingAtMs = nowMs, restingSinceMs = null) to false
    }

    /**
     * Ignores spins for a grace window after a shake. A shake ends with the phone being set
     * back down and straightened, and that set-down can easily turn it a good way round.
     */
    fun afterShake(nowMs: Long): SpinTracker =
        cleared().copy(suppressedUntilMs = nowMs + DUNE_SHAKE_SPIN_GRACE_MS)
}

/**
 * Whether a spin is under way, so shake jolts should not be counted.
 *
 * A phone spun hard on a table wobbles and scrapes, and on a Pixel 4a (2026-09-25) a 2.5 turn/s
 * spin produced three jolts past the shake threshold — a spin that revealed. The first guard
 * (any turning while flat) then swallowed every real shake, because a phone shaken in the hand
 * is held near flat and twists as it goes. What separates them is direction: a spin keeps
 * turning one way and builds up degrees, a shake twists back and forth and cancels itself out.
 * So only a spin that has already gone a fair way round, or one that has just fired and is
 * coasting to a stop, silences the shake detector.
 */
fun SpinTracker.ownsMotion(nowMs: Long): Boolean =
    // Still turning, not paused between half-turns: a shake straight after a half-turn counts.
    (abs(degrees) >= SPIN_OWNS_MOTION_DEGREES && nowMs - lastMovingAtMs < SPIN_DIRECTION_PAUSE_MS) ||
        (lastFiredAtMs != 0L && nowMs - lastFiredAtMs < SPIN_SHAKE_QUIET_MS)

/**
 * Counts taps on the display toward the on-screen fallback for ending an agent turn.
 *
 * Three quick taps rather than two: the display phone is handled all game, and two touches in
 * quick succession are easy to make by accident while picking it up or straightening it.
 */
data class TapCounter(val count: Int = 0, val lastTapAtMs: Long = 0L) {
    /** Returns the updated counter and whether this tap completes a triple tap. */
    fun observe(nowMs: Long): Pair<TapCounter, Boolean> {
        val continues = count > 0 && nowMs - lastTapAtMs <= TRIPLE_TAP_GAP_MS
        val next = if (continues) count + 1 else 1
        return if (next >= 3) TapCounter() to true else TapCounter(next, nowMs) to false
    }
}

enum class ShakeVerdict { WAIT, REVEAL, CANCEL }

/**
 * Decides a shake that has been detected but not yet acted on.
 *
 * A hard spin starts with a flick, and the flick can land the third jolt before the spin has
 * gone far enough round for [ownsMotion] to claim it (Pixel 4a, 2026-09-25: three jolts in
 * 0.75 s, the last at the flick, revealed a player mid-spin). So a shake is held for
 * [SHAKE_CONFIRM_MS] first. If a spin takes over in that time it was never a shake; a real
 * one, done in the hand, only twists back and forth and never builds a spin.
 */
fun resolvePendingShake(detectedAtMs: Long, spin: SpinTracker, nowMs: Long): ShakeVerdict = when {
    spin.ownsMotion(nowMs) || spin.lastFiredAtMs >= detectedAtMs -> ShakeVerdict.CANCEL
    nowMs - detectedAtMs >= SHAKE_CONFIRM_MS -> ShakeVerdict.REVEAL
    else -> ShakeVerdict.WAIT
}

data class ShakeTracker(
    val jolts: List<Long> = emptyList(),   // timestamps, newest last
    val lastFiredAtMs: Long = 0L,
)

/**
 * Feeds one linear-acceleration sample (gravity already subtracted) into the tracker.
 * Returns the updated tracker and whether this observation is a reveal. Counting repeated
 * jolts is what separates a shake from a brisk lift.
 */
fun ShakeTracker.observe(x: Float, y: Float, z: Float, nowMs: Long): Pair<ShakeTracker, Boolean> {
    if (lastFiredAtMs != 0L && nowMs - lastFiredAtMs < DUNE_SHAKE_COOLDOWN_MS) return this to false

    val magnitude = sqrt(x * x + y * y + z * z)
    if (magnitude < DUNE_SHAKE_ACCEL_MS2) return this to false

    val last = jolts.lastOrNull()
    if (last != null && nowMs - last < DUNE_SHAKE_MIN_GAP_MS) return this to false

    val recent = (jolts + nowMs).filter { nowMs - it <= DUNE_SHAKE_WINDOW_MS }
    if (recent.size < DUNE_SHAKE_COUNT) return copy(jolts = recent) to false

    return ShakeTracker(jolts = emptyList(), lastFiredAtMs = nowMs) to true
}
