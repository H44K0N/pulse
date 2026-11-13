package dev.tischbein.pulse.session

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.tischbein.pulse.MainActivity
import dev.tischbein.pulse.R
import dev.tischbein.pulse.coaching.CoachingState
import dev.tischbein.pulse.coaching.PhaseKind
import dev.tischbein.pulse.coaching.SessionMode
import java.util.Locale

/**
 * The persistent notification of a running session: current HR and phase, plus
 * Pause/Resume and End buttons.
 *
 * Android notes:
 * - A foreground service MUST show a notification. On Android 13+ it is only visible if the
 *   user granted POST_NOTIFICATIONS; without it the service still runs (the notification
 *   then only shows in the system's "active apps" list).
 * - On Android 14+ the user can swipe this notification away; the session keeps running.
 * - Low importance channel: no sound or vibration on every update (the voice coach is the
 *   audio).
 */
class SessionNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Running session", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Heart rate and phase while a session is running"
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    fun build(
        mode: SessionMode,
        state: CoachingState?,
        sourceLabel: String,
        /** Non-null when the voice coach is off (TTS failed); shown as a one-line warning. */
        voiceProblem: String? = null,
    ): Notification {
        val openApp =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_heart)
                .setContentTitle(title(mode, state))
                .setContentText(text(state, sourceLabel))
                .setContentIntent(openApp)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_WORKOUT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                // Android 12+ may delay a foreground-service notification by up to 10 s; a
                // workout should show its notification immediately.
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (voiceProblem != null) builder.setSubText("Voice off: $voiceProblem")
        if (state != null && state.started && !state.finished) {
            if (state.paused) {
                builder.addAction(0, "Resume", serviceAction(SessionService.ACTION_RESUME, REQUEST_RESUME))
            } else {
                builder.addAction(0, "Pause", serviceAction(SessionService.ACTION_PAUSE, REQUEST_PAUSE))
            }
            builder.addAction(0, "End", serviceAction(SessionService.ACTION_END, REQUEST_END))
        }
        return builder.build()
    }

    /** Updates the visible notification (no-op without the notification permission). */
    fun update(notification: Notification) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, SessionService::class.java).setAction(action),
            // IMMUTABLE: required since Android 12, and nobody else may change what the button does.
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun title(mode: SessionMode, state: CoachingState?): String {
        val modeName = if (mode == SessionMode.INTERVAL_4X4) "4x4" else "Easy"
        val phase = state?.phaseKind ?: return "Pulse: starting $modeName"
        val phaseName =
            when (phase) {
                PhaseKind.WARMUP -> "Warmup"
                PhaseKind.WORK -> "Interval ${state.intervalNumber} of ${state.intervalCount}"
                PhaseKind.RECOVERY -> "Recovery ${state.intervalNumber} of ${state.intervalCount}"
                PhaseKind.COOLDOWN -> "Cooldown"
                PhaseKind.EASY -> "Easy run"
            }
        return "$modeName: $phaseName"
    }

    private fun text(state: CoachingState?, sourceLabel: String): String {
        if (state == null || !state.started) return "Connecting to $sourceLabel"
        if (state.finished) return "Session complete"
        val parts = mutableListOf<String>()
        parts += if (state.sensorLost) "Sensor signal lost" else "HR ${state.smoothedHr ?: "--"}"
        state.phaseRemainingMs?.let { parts += "${formatDuration(it)} left" }
        if (state.paused) parts += "PAUSED"
        return parts.joinToString(" · ")
    }

    companion object {
        const val CHANNEL_ID = "session"
        const val NOTIFICATION_ID = 1
        private const val REQUEST_PAUSE = 1
        private const val REQUEST_RESUME = 2
        private const val REQUEST_END = 3
    }
}

/** 754_000 ms -> "12:34"; hours shown when needed ("1:02:03"). */
fun formatDuration(ms: Long): String {
    val totalSeconds = maxOf(0L, ms) / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}
