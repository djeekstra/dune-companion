package io.github.deepakgsingh.dunecompanion.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import io.github.deepakgsingh.dunecompanion.ClipSpec
import io.github.deepakgsingh.dunecompanion.DUNE_REVEAL_END_CUE
import io.github.deepakgsingh.dunecompanion.DUNE_TURN_END_CUE
import io.github.deepakgsingh.dunecompanion.DUNE_TURN_REJECTED_CUE
import io.github.deepakgsingh.dunecompanion.DuneActionResult
import io.github.deepakgsingh.dunecompanion.DunePhase
import io.github.deepakgsingh.dunecompanion.DuneSession
import io.github.deepakgsingh.dunecompanion.GravityFilter
import io.github.deepakgsingh.dunecompanion.ShakeTracker
import io.github.deepakgsingh.dunecompanion.SpinTracker
import io.github.deepakgsingh.dunecompanion.Steward
import io.github.deepakgsingh.dunecompanion.advanceDunePhase
import io.github.deepakgsingh.dunecompanion.endDuneAgentTurn
import io.github.deepakgsingh.dunecompanion.finishDuneRevealTurn
import io.github.deepakgsingh.dunecompanion.isSpinFlat
import io.github.deepakgsingh.dunecompanion.observe
import io.github.deepakgsingh.dunecompanion.ownsMotion
import io.github.deepakgsingh.dunecompanion.ShakeVerdict
import io.github.deepakgsingh.dunecompanion.resolvePendingShake
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val TAG = "DuneTilt"

// TURNS takes both gestures. COMBAT takes only the shake, to mark combat resolved: it is the
// one phase the automated watcher never advances on its own.
private fun DuneSession.acceptsSpin(): Boolean =
    active && !showingWinner && phase == DunePhase.TURNS && !paused && currentPlayer != null

private fun DuneSession.acceptsShake(): Boolean =
    active && !showingWinner && !paused &&
        (phase == DunePhase.COMBAT || (phase == DunePhase.TURNS && currentPlayer != null))

private fun DuneSession.acceptsGestures(): Boolean = acceptsSpin() || acceptsShake()

/**
 * Turns the phone into the table's input device: spin it a full turn lying face up to end an
 * agent turn, shake it to reveal. Only holds sensor registrations while a gesture can mean
 * something.
 *
 * The sensor list is published regardless of arming, so the debug overlay can
 * answer "does this phone even have a gyroscope?" before a game has been started.
 */
suspend fun runGestureWatcher(context: Context) = coroutineScope {
    val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    if (sensors == null) {
        Log.w(TAG, "no SensorManager; gestures disabled")
        return@coroutineScope
    }
    Steward.updateGestures { g ->
        g.copy(sensors = sensors.getSensorList(Sensor.TYPE_ALL)
            .filter { it.type in REPORTED_TYPES }
            .map { "${REPORTED_TYPES.getValue(it.type)}: ${it.name} (${it.power} mA)" })
    }

    val accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val gyroscope = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    // Gyro-fused, so it takes gravity out correctly even while the phone is tilting — which the
    // software filter cannot: its gravity estimate lags a quick tilt, and for a moment the
    // "gravity-free" remainder is most of gravity. On a Pixel 4a (2026-09-25) that made tilts
    // count as shake jolts. Some devices' version of this sensor reports only zeros, so
    // the software path stays as the fallback — see GestureListener.onLinear.
    val linear = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    if (accelerometer == null) Log.w(TAG, "no accelerometer; shake disabled")
    if (gyroscope == null) Log.w(TAG, "no gyroscope; spin disabled, triple tap still works")

    val listener = GestureListener(hardwareLinear = linear != null)

    // The tap fallback goes through the same path as a spin, so it is refused and cued the same.
    launch {
        Steward.tapTurnEnd.collect {
            if (Steward.dune.value.acceptsSpin()) {
                Steward.updateGestures { it.copy(taps = it.taps + 1) }
                listener.endAgentTurn(System.currentTimeMillis(), source = "tap")
            }
        }
    }

    var registered = false
    try {
        Steward.dune
            .map { it.acceptsGestures() }
            .distinctUntilChanged()
            .collect { accepting ->
                if (accepting && !registered) {
                    listener.reset()
                    accelerometer?.let { sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
                    gyroscope?.let { sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
                    linear?.let { sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
                    registered = true
                    Steward.updateGestures { it.copy(armed = true) }
                    Log.i(TAG, "armed")
                } else if (!accepting && registered) {
                    sensors.unregisterListener(listener)
                    registered = false
                    Steward.updateGestures { it.copy(armed = false, spinDegrees = 0f, shakeJolts = 0) }
                    Log.i(TAG, "disarmed")
                }
            }
    } finally {
        if (registered) sensors.unregisterListener(listener)
    }
}

private val REPORTED_TYPES = mapOf(
    Sensor.TYPE_ACCELEROMETER to "accel",
    Sensor.TYPE_GYROSCOPE to "gyro",
    Sensor.TYPE_GRAVITY to "gravity",
    Sensor.TYPE_LINEAR_ACCELERATION to "linear",
    Sensor.TYPE_GAME_ROTATION_VECTOR to "game-rotation",
    Sensor.TYPE_PROXIMITY to "proximity",
)

private class GestureListener(private val hardwareLinear: Boolean) : SensorEventListener {
    // Sensor callbacks arrive on the main looper, reset() on the arming coroutine's thread.
    private val lock = Any()
    private var gravity = GravityFilter()
    private var spin = SpinTracker()
    private var shakes = ShakeTracker()
    private var lastGyroNs = 0L
    private var lastHeartbeatMs = 0L
    private var lastPublishMs = 0L
    private var pendingShakeAtMs: Long? = null
    // Which source feeds the shake detector. Starts on the hardware sensor when there is one
    // and drops to the software split if it turns out to be one of the all-zero phantoms.
    private var useHardwareLinear = hardwareLinear
    private var linearSamples = 0
    private var linearNonZero = false

    fun reset() = synchronized(lock) {
        gravity = GravityFilter()
        spin = SpinTracker()
        shakes = ShakeTracker()
        lastGyroNs = 0L
        pendingShakeAtMs = null
        useHardwareLinear = hardwareLinear
        linearSamples = 0
        linearNonZero = false
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) = synchronized(lock) {
        val now = System.currentTimeMillis()
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> onAccel(event, now)
            Sensor.TYPE_GYROSCOPE -> onGyro(event, now)
            Sensor.TYPE_LINEAR_ACCELERATION -> onLinear(event, now)
        }
        publish(now)
    }

    private fun flat() = isSpinFlat(gravity.z)

    private fun onAccel(event: SensorEvent, now: Long) {
        val (rawX, rawY, rawZ) = Triple(event.values[0], event.values[1], event.values[2])
        gravity = gravity.observe(rawX, rawY, rawZ)
        if (!useHardwareLinear) {
            val (lx, ly, lz) = gravity.linear(rawX, rawY, rawZ)
            feedShake(lx, ly, lz, now, source = "soft")
        }
        heartbeat(now)
    }

    private fun onLinear(event: SensorEvent, now: Long) {
        if (!useHardwareLinear) return
        val (lx, ly, lz) = Triple(event.values[0], event.values[1], event.values[2])
        if (!linearNonZero) {
            linearNonZero = lx != 0f || ly != 0f || lz != 0f
            // A real sensor is never exactly zero on all three axes for a hundred samples; a
            // phantom always is.
            if (!linearNonZero && ++linearSamples >= 100) {
                Log.w(TAG, "hardware linear acceleration reports only zeros; using the software split")
                useHardwareLinear = false
                return
            }
        }
        feedShake(lx, ly, lz, now, source = "hw")
    }

    private fun feedShake(lx: Float, ly: Float, lz: Float, now: Long, source: String) {
        if (spin.ownsMotion(now)) {
            if (shakes.jolts.isNotEmpty()) Log.d(TAG, "jolts cleared: phone is spinning")
            shakes = shakes.copy(jolts = emptyList())
        } else {
            val (next, shaken) = shakes.observe(lx, ly, lz, now)
            if (next.jolts.size > shakes.jolts.size || shaken) {
                Log.d(TAG, "jolt ${if (shaken) 3 else next.jolts.size}/3 (%.1f m/s², %s)"
                    .format(kotlin.math.sqrt(lx * lx + ly * ly + lz * lz), source))
            }
            shakes = next
            if (shaken && pendingShakeAtMs == null) pendingShakeAtMs = now
        }
        pendingShakeAtMs?.let { detectedAt ->
            when (resolvePendingShake(detectedAt, spin, now)) {
                ShakeVerdict.WAIT -> Unit
                ShakeVerdict.CANCEL -> {
                    Log.i(TAG, "shake cancelled: it was the start of a spin")
                    pendingShakeAtMs = null
                }
                ShakeVerdict.REVEAL -> {
                    pendingShakeAtMs = null
                    reveal(now)
                }
            }
        }
    }

    private fun onGyro(event: SensorEvent, now: Long) {
        // Sensor timestamps, not wall clock: dt has to be the time between the samples
        // themselves, and delivery can bunch up behind a busy main thread.
        val dtS = if (lastGyroNs == 0L) 0f else ((event.timestamp - lastGyroNs) / 1e9f).coerceIn(0f, 0.1f)
        lastGyroNs = event.timestamp
        if (!gravity.primed) return
        val rate = event.values[2]
        val flat = flat()
        val (next, spun) = spin.observe(rate, dtS, flat, now)
        // A spin that got somewhere and was then dropped is exactly what tuning needs to see,
        // with the reason, so a table session's logs say why a spin did not count.
        if (!spun && spin.degrees != 0f && next.degrees == 0f && next.lastIgnoredAtMs == spin.lastIgnoredAtMs) {
            val why = when {
                !flat -> "not flat"
                now < spin.suppressedUntilMs -> "post-shake grace"
                else -> "stalled"
            }
            Log.i(TAG, "spin dropped at %.0f° (%s; g=%.2f,%.2f,%.2f rate=%.2f)".format(
                spin.degrees, why, gravity.x, gravity.y, gravity.z, rate))
        }
        if (next.lastIgnoredAtMs != spin.lastIgnoredAtMs) {
            Log.i(TAG, "spin ignored: it began before the phone was lying still (set down with a twist?)")
        }
        if (!spun && abs(spin.degrees) < 90f && abs(next.degrees) >= 90f) {
            Log.d(TAG, "spin passing 90° (rate=%.2f rad/s)".format(rate))
        }
        spin = next
        if (spun) endAgentTurn(now, source = "spin")
    }

    fun endAgentTurn(now: Long, source: String) {
        val outcome = withDuneLock {
            val previous = Steward.dune.value
            val result = endDuneAgentTurn(previous, now)
            if (result is DuneActionResult.Success) {
                applyDuneResult(result, playCue = false, previous = previous, turnEndCue = DUNE_TURN_END_CUE)
            }
            result
        }
        when (outcome) {
            is DuneActionResult.Success -> {
                Log.i(TAG, "$source: agent turn ended")
                if (source == "spin") Steward.updateGestures { it.copy(spins = it.spins + 1) }
            }
            is DuneActionResult.Error -> {
                Log.i(TAG, "$source rejected: ${outcome.message}")
                if (source == "spin") Steward.updateGestures { it.copy(spinsRejected = it.spinsRejected + 1) }
                Steward.emitAudioClips(listOf(ClipSpec(DUNE_TURN_REJECTED_CUE, boostMillibels = 600)))
            }
        }
    }

    private fun reveal(now: Long) {
        val outcome = withDuneLock {
            val previous = Steward.dune.value
            // In combat the shake means "combat is resolved".
            val inCombat = previous.phase == DunePhase.COMBAT
            val result =
                if (inCombat) advanceDunePhase(previous, now)
                else finishDuneRevealTurn(previous, now)
            if (result is DuneActionResult.Success) {
                applyDuneResult(result, previous = previous,
                    turnEndCue = DUNE_REVEAL_END_CUE.takeUnless { inCombat })
            }
            result
        }
        when (outcome) {
            is DuneActionResult.Success -> {
                Log.i(TAG, "shake: ${outcome.session.phase.wireName}")
                Steward.updateGestures { it.copy(shakes = it.shakes + 1) }
            }
            is DuneActionResult.Error -> Log.i(TAG, "shake rejected: ${outcome.message}")
        }
        spin = spin.afterShake(now)
    }

    // Throttled so the overlay redraws a few times a second, not at 50 Hz per sensor.
    private fun publish(now: Long) {
        if (now - lastPublishMs < 150L) return
        lastPublishMs = now
        val g = Triple(gravity.x, gravity.y, gravity.z)
        val flat = flat()
        val degrees = spin.degrees
        val jolts = shakes.jolts.count { now - it <= 1_000L }
        Steward.updateGestures { it.copy(gravity = g, flat = flat, spinDegrees = degrees, shakeJolts = jolts) }
    }

    private fun heartbeat(now: Long) {
        if (now - lastHeartbeatMs < 2_000L) return
        lastHeartbeatMs = now
        Log.d(TAG, "gravity %.2f,%.2f,%.2f flat=%s spin=%.0f shake=%s".format(
            gravity.x, gravity.y, gravity.z, flat(), spin.degrees, if (useHardwareLinear) "hw" else "soft"))
    }
}
