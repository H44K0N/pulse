package dev.tischbein.pulse.ui

import dev.tischbein.pulse.data.db.SyncOverview
import dev.tischbein.pulse.data.db.SyncState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Sync as plain values for Settings and the session rows (unit-tested in SyncModelsTest).
 * The owner's rule: quiet when fine, visible when not. With sync off (no URL or token) nothing
 * about sync is shown anywhere except the Settings group itself ("Off").
 */

/** The small mark on a session row / detail. None for a synced session or with sync off. */
enum class SyncBadge(val label: String) {
    WAITING("Not synced"),
    REJECTED("Upload failed"),
}

fun syncBadge(
    syncState: String,
    syncConfigured: Boolean,
): SyncBadge? =
    when {
        !syncConfigured -> null
        syncState == SyncState.PENDING -> SyncBadge.WAITING
        syncState == SyncState.FAILED -> SyncBadge.REJECTED
        else -> null
    }

/** Settings > Sync: the header line and the status rows. */
data class SyncPanel(
    /** The collapsed group's one-line summary: "Off", "All synced · today 14:02", "2 waiting", ... */
    val summary: String,
    /** Something needs the user (wrong token, server unreachable, rejected sessions): warning color. */
    val attention: Boolean,
    val configured: Boolean,
    /** "today 14:02" / "never". Null with sync off. */
    val lastSync: String? = null,
    /** "3 sessions" waiting to upload, null if none. */
    val waiting: String? = null,
    /** Why uploads are not getting through right now ("Wrong API token · today 14:05"). */
    val problem: String? = null,
    /** "1 session rejected by the server: <reason>", null if none. */
    val rejected: String? = null,
) {
    companion object {
        val OFF = SyncPanel(summary = "Off", attention = false, configured = false)
    }
}

fun syncPanel(
    configured: Boolean,
    overview: SyncOverview,
    nowMs: Long,
    zone: ZoneId,
): SyncPanel {
    if (!configured) return SyncPanel.OFF
    val o = overview
    val lastSync = o.lastSyncedAtEpochMs?.let { whenText(it, nowMs, zone) }
    val problem = o.lastError?.let { e -> e + (o.lastErrorAtEpochMs?.let { " · ${whenText(it, nowMs, zone)}" } ?: "") }
    val rejected =
        if (o.failed > 0) "${sessionsText(o.failed)} rejected by the server" + (o.rejectedReason?.let { ": $it" } ?: "") else null
    val summary =
        when {
            o.lastError != null -> "Not syncing: ${o.lastError}"
            o.failed > 0 -> "${sessionsText(o.failed)} rejected"
            o.pending > 0 -> "${o.pending} waiting"
            lastSync != null -> "All synced · $lastSync"
            else -> "On"
        }
    return SyncPanel(
        summary = summary,
        attention = o.lastError != null || o.failed > 0,
        configured = true,
        lastSync = lastSync ?: "never",
        waiting = if (o.pending > 0) sessionsText(o.pending) else null,
        problem = problem,
        rejected = rejected,
    )
}

private fun sessionsText(n: Int) = if (n == 1) "1 session" else "$n sessions"

private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
private val DATE_TIME = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH)

/** "today 14:02", "yesterday 21:30", "3 Nov 08:15". */
fun whenText(
    epochMs: Long,
    nowMs: Long,
    zone: ZoneId,
): String {
    val t = Instant.ofEpochMilli(epochMs).atZone(zone)
    val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), zone)
    return when (t.toLocalDate()) {
        today -> "today ${TIME.format(t)}"
        today.minusDays(1) -> "yesterday ${TIME.format(t)}"
        else -> DATE_TIME.format(t)
    }
}
