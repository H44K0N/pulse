package dev.tischbein.pulse.time

/**
 * A monotonic clock in milliseconds.
 *
 * Everything time-related in :core (sample timestamps, the coaching engine, the simulator)
 * reads time through this interface instead of calling the system clock directly. That is
 * what lets tests run a 45-minute workout in a few milliseconds with virtual time, while the
 * app uses real time.
 *
 * Requirements: values never go backwards, and the same clock is used for sample timestamps
 * and engine ticks. On Android use `SystemClock.elapsedRealtime()` (keeps counting in deep
 * sleep, unaffected by wall-clock changes). Wall-clock dates (`started_at`) are the app's job.
 */
fun interface MillisClock {
    fun nowMs(): Long
}
