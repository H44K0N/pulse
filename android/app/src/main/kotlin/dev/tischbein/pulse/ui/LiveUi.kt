package dev.tischbein.pulse.ui

import dev.tischbein.pulse.coaching.CoachingState
import dev.tischbein.pulse.coaching.PhaseKind
import dev.tischbein.pulse.coaching.SessionMode
import dev.tischbein.pulse.coaching.ZoneState
import dev.tischbein.pulse.sensor.ConnectionState
import dev.tischbein.pulse.sensor.DeviceStatus
import dev.tischbein.pulse.session.formatDuration
import dev.tischbein.pulse.voice.VoiceStatus

/*
 * What the live screen shows, as plain values computed from the engine state. Kept free of
 * Compose so the mapping (texts, zone labels, time formats) is covered by plain unit tests;
 * LiveScreen.kt only lays these out and picks colors.
 */

/** Zone indicator: never color alone, always an arrow/word too (colorblind-safe). */
enum class ZoneIndicator(val label: String) {
    BELOW("▼ BELOW ZONE"),
    IN("● IN ZONE"),
    ABOVE("▲ ABOVE ZONE"),
    NO_SIGNAL("SENSOR SIGNAL LOST"),
    WAITING("WAITING FOR HEART RATE"),
}

data class LiveUi(
    /** Smoothed HR, or "--" when unknown (start, sensor gap). */
    val hrText: String,
    val zone: ZoneIndicator,
    /** "Warmup", "Interval 2", "Recovery", "Cooldown", "Easy run", "Starting..." */
    val phaseTitle: String,
    /** "3:12 left" (phase time remaining), or null when there is no fixed end. */
    val remainingText: String?,
    /** "Interval 2 of 4" in 4x4; "12:34 / 45:00" or "12:34" elapsed in easy mode. */
    val progressText: String?,
    /** "Target 162-181", or null before the session started. */
    val targetText: String?,
    val paused: Boolean,
    /** One-line warning when the voice coach is off, else null. */
    val voiceWarning: String?,
)

/** [easyTargetMs]: the easy session's target duration, null if open-ended or 4x4. */
fun liveUi(
    state: CoachingState,
    voice: VoiceStatus,
    easyTargetMs: Long? = null,
): LiveUi {
    val started = state.started && state.phaseKind != null
    return LiveUi(
        hrText = state.smoothedHr?.toString() ?: "--",
        zone = zoneIndicator(state.zoneState, state.sensorLost),
        phaseTitle = if (started) phaseTitle(state.phaseKind!!, state.intervalNumber) else "Starting...",
        remainingText = if (state.mode == SessionMode.INTERVAL_4X4) state.phaseRemainingMs?.let { "${formatDuration(it)} left" } else null,
        progressText = if (started) progressText(state, easyTargetMs) else null,
        targetText = if (started && state.targetLow != null) "Target ${state.targetLow}-${state.targetHigh}" else null,
        paused = state.paused,
        voiceWarning = (voice as? VoiceStatus.Unavailable)?.let { "Voice off: ${it.reason}" },
    )
}

fun zoneIndicator(zone: ZoneState, sensorLost: Boolean): ZoneIndicator =
    when (zone) {
        ZoneState.BELOW -> ZoneIndicator.BELOW
        ZoneState.IN -> ZoneIndicator.IN
        ZoneState.ABOVE -> ZoneIndicator.ABOVE
        ZoneState.UNKNOWN -> if (sensorLost) ZoneIndicator.NO_SIGNAL else ZoneIndicator.WAITING
    }

fun phaseTitle(kind: PhaseKind, intervalNumber: Int?): String =
    when (kind) {
        PhaseKind.WARMUP -> "Warmup"
        PhaseKind.WORK -> "Interval $intervalNumber"
        PhaseKind.RECOVERY -> "Recovery"
        PhaseKind.COOLDOWN -> "Cooldown"
        PhaseKind.EASY -> "Easy run"
    }

private fun progressText(
    state: CoachingState,
    easyTargetMs: Long?,
): String? =
    when (state.mode) {
        SessionMode.INTERVAL_4X4 -> state.intervalNumber?.let { "Interval $it of ${state.intervalCount}" }
        SessionMode.EASY -> {
            val elapsed = formatDuration(state.elapsedActiveMs)
            if (easyTargetMs == null) elapsed else "$elapsed / ${formatDuration(easyTargetMs)}"
        }
    }

/** The small status row: battery, skin contact, sync. */
fun statusItems(device: DeviceStatus): List<String> {
    val sensor =
        if (device.simulatorProfile != null) {
            listOf("Simulator")
        } else {
            listOf(
                if (device.connection == ConnectionState.CONNECTED) {
                    "Battery ${device.batteryPercent?.let { "$it %" } ?: "--"}"
                } else {
                    "Strap ${device.connection.name.lowercase()}"
                },
                when (device.skinContact) {
                    true -> "Contact OK"
                    false -> "NO CONTACT"
                    null -> "Contact ?"
                },
            )
        }
    // Sync arrives in Milestone 5; until then every session lives on the phone only.
    return sensor + "Saved on phone"
}
