# Pulse

Polar H10 interval coach for Android: connects to the strap, logs every session,
and coaches 4x4 intervals and easy runs by voice. Sessions sync to a small
self-hosted FastAPI backend.

Work in progress. See `docs/session-format.md` for the session data contract.

## Build and test

Requirements: JDK 21 (JDK 17 is enough for `:core` alone) and the Android SDK with
`platforms;android-37.0` (set `ANDROID_HOME`, or `sdk.dir` in `android/local.properties`).

```sh
cd android
./gradlew :core:check :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

- `:core` is pure Kotlin: coaching engine, sensor interface, simulator. JVM tests only.
- `:app` is the Android app. Its unit tests run on the JVM too (Room via Robolectric,
  which downloads an Android runtime jar from Maven Central on the first run).
- Every build runs `checkNoGooglePlayServices`, which fails if any dependency pulls in
  Google Play Services or Firebase.
- The debug APK ends up in `android/app/build/outputs/apk/debug/app-debug.apk`.

## Install and try it

1. Install the APK (`adb install -r app-debug.apk`, or copy it to the phone and open it;
   allow "install unknown apps" for your file manager once).
2. Open Pulse and grant "Nearby devices" (Bluetooth) and notifications.
3. Without the strap: Settings > "Debug: simulator", pick a profile, Save. The home screen
   shows "Simulator: <profile>"; choose a mode and Start.
   Easy mode takes an optional target duration on the home screen (empty = open-ended).
4. With the strap: put it on (it only wakes up with skin contact), tap
   "Scan / connect H10" and pick it. Pulse remembers it and reconnects automatically the
   next time the app is opened.
5. If a session stops when the screen is off, set Pulse's battery usage to "Unrestricted"
   (Settings > Battery in Pulse links to the right system page).

## How a session is recorded

A session runs in a foreground service (notification with heart rate, phase, Pause/End),
so it keeps going with the screen off. Samples, cues and phases are written to the local
database every 5 seconds. When the session ends, the complete result replaces them.

If Android kills the app mid-session, the data up to the last write stays in the
database. On the next start the session is marked **incomplete** (it is not resumed) and
is exported with `"complete": false`.

## Live screen and voice

- The live screen keeps the display on while it is visible (only then). The session itself
  runs in the service and does not need the screen.
- End = press and hold the End button (a tap only shows the hint).
- Cues are spoken with Android text-to-speech in English. Music is ducked (turned down)
  while the coach talks, not paused; spoken-word apps (podcasts) may pause instead, that is
  their choice. Cue volume follows the media volume on most phones.
- If no English TTS voice is installed, the session runs silently and the live screen and
  notification say "Voice off". Install one in Android Settings > System > Languages >
  Text-to-speech output.
- After the session the summary is spoken and the summary screen is shown.
