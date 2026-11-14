package dev.tischbein.pulse

import dev.tischbein.pulse.coaching.MS_PER_HOUR
import dev.tischbein.pulse.coaching.MS_PER_SECOND

/*
 * THE place for app-side numbers: recording, screens, history. The coaching values (protocol,
 * zones, thresholds, cue timing) are NOT here: they live in :core's CoachingConfig.kt. Voice
 * delivery timing lives in voice/VoiceConstants.kt. Settings limits (ages, minutes, %) are in
 * data/settings/AppSettings.kt next to the validation that uses them.
 */
object AppConstants {
    // ---- Recording (SessionService) ----------------------------------------------------

    /** How often recorded data is written to Room: the most a crash can lose. */
    const val FLUSH_INTERVAL_MS: Long = 5 * MS_PER_SECOND

    /** Notification updates at most this often (Android drops updates from apps that post too often). */
    const val NOTIFICATION_INTERVAL_MS: Long = 5 * MS_PER_SECOND

    /** Safety net on the session's CPU wake lock: a bug can never keep the phone awake forever. */
    const val WAKE_LOCK_TIMEOUT_MS: Long = 6 * MS_PER_HOUR

    // ---- Live screen -------------------------------------------------------------------

    /** Seconds of HR in the live sparkline (shown on tap). */
    const val LIVE_WINDOW_S: Int = 120

    /** The strap's battery is only mentioned on the live/train screens below this level. */
    const val LOW_BATTERY_PCT: Int = 20

    /** How long End must be held. Long enough not to happen by accident, short enough mid-run. */
    const val HOLD_TO_END_MS: Long = 1_200L

    // ---- History -----------------------------------------------------------------------

    /** Progress compares the last this-many days with the same span before (2 x 21 = 6 weeks). */
    const val PROGRESS_WINDOW_DAYS: Int = 21

    /** Sessions listed on the Progress page before "Show all". */
    const val RECENT_SESSIONS: Int = 8

    /** Points in the small HR line of a session row (stored per session in Room). */
    const val SPARKLINE_POINTS: Int = 48

    /** Seconds per point of the HR graph on the session detail screen. */
    const val DETAIL_CHART_STEP_S: Int = 5

    // ---- Settings ----------------------------------------------------------------------

    /** Settings save this long after the last change (one write per burst of taps). */
    const val SETTINGS_AUTOSAVE_DEBOUNCE_MS: Long = 600L

    // ---- Sync (data/sync) --------------------------------------------------------------

    /** Time allowed to open the connection to the server (DNS + TCP + TLS). */
    const val SYNC_CONNECT_TIMEOUT_MS: Int = 15_000

    /** Time allowed between bytes of the server's answer (a big session is ~1 MB to send). */
    const val SYNC_READ_TIMEOUT_MS: Int = 30_000

    /** First retry after a failed upload; WorkManager doubles it each time, up to 5 hours. */
    const val SYNC_BACKOFF_INITIAL_MS: Long = 60 * MS_PER_SECOND

    /**
     * After the server URL or token is edited, sync waits this long. The settings autosave
     * while typing, so without the wait a half-typed URL ("https://pulse-api.tisch") could
     * receive the token; with it, only the URL that stays in the field is used.
     */
    const val SYNC_SETTINGS_SETTLE_MS: Long = 20 * MS_PER_SECOND

    /** At most this much of a server's answer is read and kept (error details are short). */
    const val SYNC_MAX_RESPONSE_CHARS: Int = 4_096

    /** At most this much of a rejection reason is stored with the session and shown. */
    const val SYNC_MAX_ERROR_CHARS: Int = 300
}
