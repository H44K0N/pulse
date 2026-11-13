package dev.tischbein.pulse.voice

import dev.tischbein.pulse.coaching.EventType
import dev.tischbein.pulse.coaching.MS_PER_SECOND

/*
 * THE place for every timing value of the voice output (the app-side counterpart of the
 * engine's CoachingConfig.kt). What the coach says and when it decides to say it lives in
 * :core; this file only covers how the phone delivers it.
 */
object VoiceConstants {
    /**
     * Stale cue rule: a cue still waiting in the queue this long after the engine produced it
     * is dropped instead of spoken. WHY: TTS speaks one sentence at a time, so a cue can wait
     * behind others (or behind a slow TTS start). "Above zone, ease off." 15 s late may be
     * plain wrong by then. 8 s (not less) because the start of each recovery is a planned
     * burst of up to three sentences ("Recover...", the recap, midway) that takes ~6 s to say.
     */
    const val STALE_CUE_MS: Long = 8 * MS_PER_SECOND

    /** Countdown numbers ("3", "2", "1") are only right at their exact second. */
    const val STALE_COUNTDOWN_MS: Long = 2 * MS_PER_SECOND

    /**
     * Never dropped as stale: the end-of-session words are not time-critical, and they are
     * the only place the user hears them (brief: spoken summary, HRmax announcement).
     */
    val NEVER_STALE: Set<EventType> = setOf(EventType.SESSION_SUMMARY, EventType.HR_MAX_LEARNED)

    /**
     * Audio focus (= music ducked) is kept this long after the last utterance before it is
     * released. WHY: "3", "2", "1" are one second apart and each takes ~0.5 s to say; without
     * a short hold the music would jump back up and duck again between every number.
     */
    const val FOCUS_HOLD_MS: Long = 1_500L

    /**
     * Safety net: if the TTS engine never reports an utterance as finished (engine crash),
     * treat it as finished after this long so the queue and audio focus are not stuck.
     */
    const val UTTERANCE_TIMEOUT_MS: Long = 20 * MS_PER_SECOND

    /**
     * After a session ends the service keeps running (and the voice alive) at most this long
     * so the spoken summary is not cut off when the service stops.
     */
    const val END_DRAIN_TIMEOUT_MS: Long = 30 * MS_PER_SECOND
}
