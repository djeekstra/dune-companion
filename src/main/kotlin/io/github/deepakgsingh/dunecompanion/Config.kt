package io.github.deepakgsingh.dunecompanion

object Config {
    // Port the admin panel is served on. The admin phone opens http://<this phone>:8080/.
    const val PANEL_PORT = 8080

    // Dune: Imperium round-steward pacing and post-game display.
    const val DUNE_BED_VOLUME = 0.75f

    const val DUNE_TIMER_FIVE_MINUTES_MS = 5 * 60_000L
    const val DUNE_TIMER_PULSE_MS = 7 * 60_000L
    const val DUNE_TIMER_TEN_MINUTES_MS = 10 * 60_000L
    const val DUNE_AUTOMATED_PHASE_MS = 10_000L
    const val DUNE_COMBAT_TO_MAKERS_DELAY_MS = 15_000L
    const val DUNE_WINNER_TTL_MS = 5 * 60_000L

    // Low-pass weight for the gravity estimate from the raw accelerometer: a ~400 ms time
    // constant at 50 Hz.
    const val DUNE_TILT_GRAVITY_ALPHA = 0.95f

    // Spin the phone a full turn, lying face up, to end an agent turn. Starting guesses, to be
    // tuned against DuneTilt logs on real phones.
    //
    // 300 rather than 360: a spun phone rarely lands where it started. Pixel 4a, 2026-09-25:
    // deliberate spins coasted to rest at 314-329 degrees, while pick-ups, nudges and slow
    // accidental turns never passed 243 — so 300 catches the spins with margin on both sides.
    const val SPIN_FIRE_DEGREES = 300f
    // Screen-normal gravity needed to count as lying on its back: 8.5 of 9.8 is a 30 degree
    // tilt. A phone on a table reads ~9.7; one lifted to be read is tilted well past this.
    const val SPIN_FLAT_MIN_Z = 8.5f
    // Below this rate the gyro is not integrated at all, so its resting bias (a few hundredths
    // of a rad/s) never accumulates into a phantom turn. 0.6 rad/s is ~35 degrees a second —
    // a lazy spin still clears it by a wide margin.
    const val SPIN_MIN_RATE_RAD_S = 0.6f
    // A spin may pause this long, to regrip between two half-turns, and carry on where it
    // left off. Longer than that and it has been abandoned.
    const val SPIN_REGRIP_MS = 2_500L
    // How long the phone must have been lying still before a spin can count. Between turns
    // it sits for seconds; a spin that begins as it is being put down is not a turn.
    const val SPIN_REST_BEFORE_MS = 1_000L
    // Resuming in the opposite direction after a pause at least this long starts afresh.
    const val SPIN_DIRECTION_PAUSE_MS = 300L
    // The whole turn, pauses included, has to happen within this, so a phone slowly worried
    // round by someone fidgeting does not eventually add up to a spin. Two half-turns with a
    // regrip take up to ~4.5 s.
    const val SPIN_WINDOW_MS = 6_000L
    const val SPIN_COOLDOWN_MS = 3_000L
    // Shake jolts are ignored once a spin has gone this far round in one direction, and for
    // the quiet time after a spin fires while the phone coasts down. See SpinTracker.ownsMotion.
    // 60: a hand shake twists ~30 degrees each way and nets out, while even a lazy flick
    // (~3 rad/s, the slowest that jolted in testing) passes 60 inside SHAKE_CONFIRM_MS.
    const val SPIN_OWNS_MOTION_DEGREES = 60f
    const val SPIN_SHAKE_QUIET_MS = 1_500L

    // Shake to reveal, and during combat to mark it resolved.
    // 20 m/s². Pixel 4a, 2026-09-25: hard tilting peaked at 12.9 m/s² on the
    // hardware linear sensor, a real shake at 46-62. 20 clears tilting with room and still
    // leaves a shake more than twice over.
    const val DUNE_SHAKE_ACCEL_MS2 = 20.0f
    const val DUNE_SHAKE_COUNT = 3
    const val DUNE_SHAKE_WINDOW_MS = 1_000L
    const val DUNE_SHAKE_MIN_GAP_MS = 100L
    const val DUNE_SHAKE_COOLDOWN_MS = 3_000L
    // How long a detected shake waits to be sure it was not the flick that starts a spin.
    // Long enough for even a lazy flick to pass SPIN_OWNS_MOTION_DEGREES; short enough to sit
    // inside the gap before the reveal cue would start anyway.
    const val SHAKE_CONFIRM_MS = 500L

    // After a shake the phone is put back down and straightened; a spin cannot fire until this
    // has passed. A swallowed spin just needs doing again, whereas a
    // phantom turn end has to be undone from the panel.
    const val DUNE_SHAKE_SPIN_GRACE_MS = 5_000L

    // Most time allowed between taps of the on-screen triple tap.
    const val TRIPLE_TAP_GAP_MS = 400L

    // Audio playback watchdog: a clip gets its measured duration plus this margin.
    const val CLIP_TIMEOUT_MARGIN_MS = 5_000L
    const val CLIP_TIMEOUT_UNKNOWN_MS = 300_000L
    // Spoken lines have no duration to measure; none of them take anywhere near this long.
    const val SPEECH_TIMEOUT_MS = 15_000L
    const val AUDIO_QUEUE_CAPACITY = 64
}
