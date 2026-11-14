package dev.tischbein.pulse.ui

import dev.tischbein.pulse.coaching.CoachingState
import dev.tischbein.pulse.coaching.PhaseKind
import dev.tischbein.pulse.coaching.ZoneState
import dev.tischbein.pulse.voice.VoiceStatus

/*
 * The live screen's basic texts, as plain values computed from the engine state (unit-tested
 * without Compose). DashboardModels.liveDashboard adds the rest.
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
    val zone: ZoneIndicator,
    /** "Warmup", "Interval 2", "Recovery", "Cooldown", "Easy run", "Starting…" */
    val phaseTitle: String,
    val paused: Boolean,
    /** Set when the voice coach is off (no TTS voice etc.); the reason is in the notification. */
    val voiceOff: Boolean,
)

fun liveUi(
    state: CoachingState,
    voice: VoiceStatus,
): LiveUi {
    val started = state.started && state.phaseKind != null
    return LiveUi(
        zone = zoneIndicator(state.zoneState, state.sensorLost),
        phaseTitle = if (started) phaseTitle(state.phaseKind!!, state.intervalNumber) else "Starting…",
        paused = state.paused,
        voiceOff = voice is VoiceStatus.Unavailable,
    )
}

fun zoneIndicator(
    zone: ZoneState,
    sensorLost: Boolean,
): ZoneIndicator =
    when (zone) {
        ZoneState.BELOW -> ZoneIndicator.BELOW
        ZoneState.IN -> ZoneIndicator.IN
        ZoneState.ABOVE -> ZoneIndicator.ABOVE
        ZoneState.UNKNOWN -> if (sensorLost) ZoneIndicator.NO_SIGNAL else ZoneIndicator.WAITING
    }

fun phaseTitle(
    kind: PhaseKind,
    intervalNumber: Int?,
): String =
    when (kind) {
        PhaseKind.WARMUP -> "Warmup"
        PhaseKind.WORK -> "Interval $intervalNumber"
        PhaseKind.RECOVERY -> "Recovery"
        PhaseKind.COOLDOWN -> "Cooldown"
        PhaseKind.EASY -> "Easy run"
    }
