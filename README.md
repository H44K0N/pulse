# Pulse

Polar H10 interval coach for Android: connects to the strap, logs every session,
and coaches 4x4 intervals and easy runs by voice. Sessions sync to a small
self-hosted FastAPI backend.

Version 0.5.0 (Milestone 5: finished sessions sync to the server). See `ARCHITECTURE.md` for how it fits together and
`docs/session-format.md` for the session data contract.

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

### Screenshots

The real screens (the same stateless composables the app uses) are rendered to PNG on the
JVM (Robolectric + Roborazzi, no device), with data from the real coaching engine and the
simulator:

```sh
./gradlew :app:recordRoborazziDebug --tests '*DesignMockups*'
```

Output: `android/app/src/test/screenshots/` (1079 x 2399 px, plus `00-contact-sheet.png`).
A normal test run skips these. The UI font is Inter (SIL Open Font License,
`android/app/licenses/Inter-OFL.txt`).

## Install and try it

1. Install the APK (`adb install -r app-debug.apk`, or copy it to the phone and open it;
   allow "install unknown apps" for your file manager once). Updating keeps your sessions:
   the database is migrated on the first start.
2. Open Pulse and grant "Nearby devices" (Bluetooth) and notifications.
3. Without the strap: Settings > Developer > Simulator on, pick a profile. Train then shows
   "Simulator"; choose a mode and Start.
4. With the strap: put it on (it only wakes up with skin contact), tap the strap card on
   Train ("Find strap") and pick it. Pulse remembers it and reconnects automatically the next
   time the app is opened. Settings > Strap changes or forgets it.
5. If a session stops when the screen is off, Settings shows "Battery optimisation is on";
   tap Fix and set Pulse to "Unrestricted".

## The screens

- **Train**: strap, mode (4x4 / Easy), a one-line plan (tap it for the phase timeline, and
  in easy mode the target duration), Start.
- **Live**: phase and interval, the session strip, the heart rate colored by zone, the
  target range and the time left. Tap the heart rate for the last two minutes (and live
  drift in easy mode). Warnings (strap reconnecting, no skin contact, battery under 20 %,
  voice off) only appear when they apply. End = press and hold until the red fill is full.
- **Summary / session detail**: time in zone, duration, average HR, HRR60 (4x4) or drift
  (easy), the HR graph and the per-interval table. "Delete session" removes it with an Undo
  snackbar.
- **Progress**: last 3 weeks vs the 3 before (tap Sessions for the training calendar), one
  trend chart (Recovery / In zone / Drift), the last 8 sessions and "Show all".
- **Settings** (saved automatically): age and HRmax, the strap; collapsed: Workout
  (4x4 durations and zones), Sync (server, see below), Developer (simulator).

## How a session is recorded

A session runs in a foreground service (notification with heart rate, phase, Pause/End),
so it keeps going with the screen off. Samples, cues and phases are written to the local
database every 5 seconds. When the session ends, the complete result replaces them.

If Android kills the app mid-session, the data up to the last write stays in the
database. On the next start the session is marked **incomplete** (it is not resumed) and
is exported with `"complete": false`.

## Sync to your server

Finished sessions (complete, or marked incomplete after a crash; never one still being
recorded) are uploaded to your own Pulse server (`server/`, see `server/DEPLOY.md`) as the
JSON of `docs/session-format.md`. Nothing else is ever sent anywhere.

1. Settings > Sync: enter the server URL (e.g. `https://pulse-api.example.com`, HTTPS only)
   and the API token (the server's `PULSE_API_TOKEN`; paste it, the eye shows it to check).
2. Tap **Test connection**: "Connected", "Cannot reach the server", "does not accept this
   token" or "must start with https://".
3. That's it. Uploads run in the background whenever the phone is online: when a session
   ends, when the app starts, 20 s after the URL/token was edited, and on **Sync now**.
   Failed attempts are retried with growing pauses (1 min, 2, 4, ... up to 5 h).

Without a URL and token the app works offline and shows nothing about sync.

What you see (only when something is not fine):

| Where | Shown | Meaning |
|---|---|---|
| Session row / detail | nothing | on the server |
| | "Not synced" (grey) | waiting: no connection yet, or the next retry |
| | "Upload failed" (amber) | the server rejected this session (too large or invalid); it is not retried automatically, **Sync now** tries once more |
| Settings > Sync header | "All synced · today 14:02" / "2 waiting" | fine |
| | "Not syncing: Wrong API token" (amber) | fix the token; the reason is also shown inside the group |

The server keeps the first upload of a session and has no delete: deleting a session in the
app does not remove it from the server. Changing the server URL to a different server
uploads the whole history there once.

Testing against a server on your computer (debug builds only, which also allow plain http
to your own machine): run the server on port 8000, `adb reverse tcp:8000 tcp:8000`, and set
the URL to `http://localhost:8000` (from the emulator: `http://10.0.2.2:8000`). Release
builds refuse any `http://` URL.

## Live screen and voice

- The live screen keeps the display on while it is visible (only then). The session itself
  runs in the service and does not need the screen.
- End = press and hold the End button until the fill is full (a tap only says "Keep holding").
- Cues are spoken with Android text-to-speech in English. Music is ducked (turned down)
  while the coach talks, not paused; spoken-word apps (podcasts) may pause instead, that is
  their choice. Cue volume follows the media volume on most phones.
- If no English TTS voice is installed, the session runs silently and the live screen and
  notification say "Voice off". Install one in Android Settings > System > Languages >
  Text-to-speech output.
- After the session the summary is spoken and the summary screen is shown.
