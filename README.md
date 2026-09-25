# Dune Companion

A round steward for **Dune: Imperium** that runs on an Android phone. One phone lies on the
table: it shows whose turn it is, times each turn, and announces the handovers and the phases
out loud. Players drive it with gestures instead of tapping. A second phone can open an admin
panel in its browser for setup, scoring and corrections.

*Fan-made. Not affiliated with or endorsed by Dire Wolf or Legendary.*

## Features

- **Gestures on the display phone:**
  - **Spin it a full turn** while it lies face up to end an agent turn. Two half-turns the same
    way also count. It must have been lying still for a second first, so setting it down with a
    twist doesn't count.
  - **Shake it** in the hand to reveal. During combat, a shake marks combat resolved.
  - **Triple-tap** the screen to end an agent turn, as a fallback.
- **Voice:** the phone's text-to-speech names whoever has the turn, and calls out phases, turn
  timers (5, 7 and 10 minutes) and hourly game time. Recorded clips replace any line if
  installed.
- **Round flow:** rounds 1–10, conflict tiers, and the automatic makers and recall phases.
  - Swordmaster, Mentat and High Council tracking.
  - Victory-point checkpoints after conflicts 5, 9 and 10.
  - Undo for the last turn and the last round.
- **Admin panel:** served by the display phone on port 8080 to any browser on the same Wi-Fi,
  or on the display phone's hotspot. The display shows a QR code for it between games.
- **Game is saved** on every change, so closing the app or a restart picks up where it was.

## Build and install

Requires JDK 17 and the Android SDK (API 34).

```sh
./gradlew testDebugUnitTest          # rules, gesture and speech tests
./gradlew assembleRelease            # shrunk APK, ~1.4 MB
adb install -r build/outputs/apk/release/dune-companion-release.apk
adb logcat -s DuneTilt DuneAudio     # gesture and audio logs
```

## Using it

1. Open the app on the display phone and allow notifications.
2. Scan the QR code with a second phone and start a game from the panel.
3. Lay the display phone face up on the table.
4. **Long-press** the display to show the gesture debug overlay. It has sensors, live spin
   degrees and fire counts, and an exit button.
5. To close the app, use **EXIT** on the display, or **Stop** on its notification.

Recorded clips are optional. Push them to the app's `files/clips/` directory, named as in
`DuneSpeech.kt` (for example `dune_phase_combat.wav`).

## Status

Early. The gestures were tuned on a Pixel 4a (Android 16):

- **Spin:** fires at 300°. Deliberate spins coasted to rest at 314–329°; accidental handling
  never passed about 240°.
- **Shake:** fires at 20 m/s² using the hardware linear-acceleration sensor. Hard tilting
  peaked at 12.9, a real shake at 46–62.

Sensor draw is about 1.7 mA rated, under 0.2% of the battery over a three-hour game.

Not yet verified:

- the panel over a hotspot
- a full three-hour game
- more phone models

Thresholds live in `Config.kt`.

## Licence

Not chosen yet. Until one is added, all rights are reserved.
