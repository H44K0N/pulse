# Architecture

Pulse is two programs: the Android app (`android/`, Kotlin + Compose) and a small FastAPI
server (`server/`). The app works fully offline; the server only receives finished sessions,
uploaded in the background once a server URL and API token are set.

## Data flow

```mermaid
flowchart LR
    strap["Polar H10<br/>(BLE, HR + RR)"] --> polar["sensor/<br/>PolarHeartRateSource"]
    sim["core: SimulatedHeartRateSource<br/>(Settings > Developer)"] -.-> source
    polar --> source(("HeartRateSource<br/>Flow of samples"))
    source --> service["session/SessionService<br/>(foreground service)"]
    service --> engine["core: CoachingEngine<br/>(pure Kotlin)"]
    engine -- "CoachingEvent (cue text)" --> voice["voice/VoiceCoach<br/>TextToSpeech + ducking"]
    engine -- "CoachingState" --> live["ui/LiveScreen"]
    service -- "samples, cues, phases<br/>every 5 s" --> room[("Room: pulse.db<br/>sessions + digest,<br/>samples, events, ...")]
    engine -- "SessionResult at the end" --> room
    room --> history["ui: Progress, all sessions,<br/>session detail"]
    service -- "session finished" --> work["data/sync/SyncWorker<br/>(WorkManager: online,<br/>backoff, unique)"]
    room -- "pending finished sessions<br/>as JSON (docs/session-format.md)" --> uploader["data/sync/SessionUploader"]
    work --> uploader
    uploader -- "POST /api/sessions<br/>HTTPS, Bearer token" --> server["server/<br/>FastAPI + SQLite"]
    uploader -- "synced / pending / failed" --> room
```

- **`:core`** (pure Kotlin, JVM tests): the coaching engine, the sensor interface and the
  simulator. No Android imports, so every coaching rule is unit-tested.
- **`:app`**: `sensor` (Polar SDK, RxJava bridged to Flow only there) → `session`
  (foreground service, recorder, live HR buffer) → `data` (Room, DataStore, JSON export) →
  `ui` (Compose). `AppContainer.kt` is the whole object graph (manual DI).
- **UI pattern**: every screen is a stateless `*Content` composable fed plain values
  (`ui/DashboardModels.kt`, unit-tested), plus a thin `*Screen` that collects Flows from
  `AppContainer`. Navigation is a few lines of state in `ui/PulseApp.kt` (three tabs and a
  small back stack), no navigation library.
- **Sync** (`data/sync/`): `SyncWorker` (WorkManager, needs a network, exponential backoff,
  one unique job) runs `SessionUploader`, which sends every finished, not yet synced session
  (oldest first) through `SyncClient` (status codes -> outcome) and `SyncHttp`
  (`HttpURLConnection`, the only network code). Per-session state in Room: `pending` →
  `synced` (201/200) or `failed` (413/422, the server will never take it); anything else
  (offline, 401, 5xx) stays `pending` with the reason and is retried. Triggers: a session
  ends, app start, URL/token edited (after 20 s, so a half-typed URL never gets the token),
  "Sync now". `ServerUrl` decides which URLs are allowed (HTTPS; `http://localhost` only in
  debug builds), matching `res/xml/network_security_config.xml`.
- **History**: each `sessions` row carries a digest (avg/max HR, time in zone, HRR60,
  drift, a 48-point sparkline) written when the session is saved, so Progress and the lists
  never read samples. The detail screen loads one session and rebuilds its `SessionResult`.

## Where to change X

| I want to... | Change |
|---|---|
| Change a coaching threshold, protocol default or zone default | `android/core/.../coaching/CoachingConfig.kt` (the only place) |
| Change what a cue says | `android/core/.../coaching/Cues.kt` |
| Add a workout mode | `SessionMode` in `CoachingModels.kt`, the engine (`CoachingEngine.kt`) and its tests, then the mode toggle in `ui/TrainContent.kt` |
| Change zones for myself | In the app: Settings > Workout (stored in DataStore, `data/settings/AppSettings.kt`) |
| Change an app-side number (flush interval, low-battery level, hold-to-end time, Progress window, list length) | `android/app/.../AppConstants.kt` |
| Change voice timing (stale cues, ducking hold) | `android/app/.../voice/VoiceConstants.kt` |
| Change a settings limit (age, minutes, zone %) | companion object of `data/settings/AppSettings.kt` |
| Change a screen | its `*Content.kt` in `ui/`; the numbers it shows come from `ui/DashboardModels.kt` |
| Change colors or fonts | `ui/theme/Theme.kt` |
| Change the database | entity in `data/db/Entities.kt`, bump the version in `PulseDatabase.kt`, add a migration in `Migrations.kt`, test it in `MigrationTest`, commit the new `app/schemas/.../N.json` |
| Change sync timing (timeouts, backoff, settle delay) | `android/app/.../AppConstants.kt` (Sync section) |
| Change what a server answer means (retry vs give up) | `data/sync/SyncClient.kt` (and `SyncClientTest`) |
| Change the upload format | `docs/session-format.md`, `data/export/SessionDocument.kt`, the server's `models.py` |

## Checks

`cd android && ./gradlew :core:check :app:assembleDebug :app:lintDebug :app:testDebugUnitTest`
runs everything (engine, Room on Robolectric, migrations, sync with a fake HTTP layer and a
local JDK HTTP server, UI models). Screenshots of the
real screens: `./gradlew :app:recordRoborazziDebug --tests '*DesignMockups*'`.
