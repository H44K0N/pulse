package dev.tischbein.pulse.time

import android.os.SystemClock

/**
 * The app's [MillisClock]: `SystemClock.elapsedRealtime()`.
 *
 * WHY this clock: it never jumps when the user or the network changes the wall clock, and,
 * unlike `uptimeMillis()`, it keeps counting while the phone is in deep sleep. Sensor sample
 * timestamps and the coaching engine must use the same clock, so both use this object.
 */
object ElapsedRealtimeClock : MillisClock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}
