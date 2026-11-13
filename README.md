# Pulse

Polar H10 interval coach for Android: connects to the strap, logs every session,
and coaches 4x4 intervals and easy runs by voice. Sessions sync to a small
self-hosted FastAPI backend.

Work in progress. See `docs/session-format.md` for the session data contract.

## Build and test

The coaching engine, sensor interface and simulator live in the pure Kotlin module
`android/core` and are tested on the JVM (JDK 17+):

```sh
cd android
./gradlew :core:check
```
