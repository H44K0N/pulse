package dev.tischbein.pulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tischbein.pulse.AppConstants
import dev.tischbein.pulse.AppContainer
import dev.tischbein.pulse.data.LoadedSession
import dev.tischbein.pulse.ui.theme.Pulse
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId

/*
 * The history screens wired to Room. They read the per-session digest rows (never samples),
 * except the detail screen, which loads one session completely.
 */

/** Progress: the 6-week window, the last few sessions and the total count, all live from Room. */
@Composable
fun ProgressScreen(
    container: AppContainer,
    hidden: Set<String>,
    modifier: Modifier = Modifier,
    onShowAll: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val from = remember(today) { today.minusDays(2L * AppConstants.PROGRESS_WINDOW_DAYS - 1).atStartOfDay(zone).toInstant().toEpochMilli() }
    val window by remember(from) { container.sessions.observeSince(from) }.collectAsStateWithLifecycle(initialValue = null)
    // One more than shown, so a session waiting for "Undo" does not leave a hole in the list.
    val recent by remember { container.sessions.observeRecent(AppConstants.RECENT_SESSIONS + 1) }.collectAsStateWithLifecycle(initialValue = null)
    val count by remember { container.sessions.observeCount() }.collectAsStateWithLifecycle(initialValue = null)
    val syncOn by rememberSyncConfigured(container)
    val w = window
    val r = recent
    val c = count
    if (w == null || r == null || c == null) {
        Box(modifier.fillMaxSize().background(Pulse.bg))
        return
    }
    val overview =
        remember(w, r, c, hidden, syncOn) {
            progressOverview(
                window = w.filter { it.id !in hidden },
                recent = r.filter { it.id !in hidden }.take(AppConstants.RECENT_SESSIONS),
                // Sessions waiting for "Undo" still exist in the database.
                totalSessions = c - hidden.size,
                today = today,
                zone = zone,
                syncConfigured = syncOn,
            )
        }
    ProgressContent(overview, modifier, onShowAll = onShowAll, onOpenSession = onOpenSession)
}

@Composable
fun AllSessionsScreen(
    container: AppContainer,
    hidden: Set<String>,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val all by remember { container.sessions.observeAll() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val syncOn by rememberSyncConfigured(container)
    val rows = remember(all, hidden, syncOn) { sessionRows(all.filter { it.id !in hidden }, zone, syncOn) }
    AllSessionsContent(rows, modifier, onBack = onBack, onOpenSession = onOpenSession)
}

/**
 * One session from Room by id: right after a session ([justFinished], Done) or from the
 * history (Back). Shows nothing while loading; a session that no longer exists goes back.
 */
@Composable
fun SessionDetailScreen(
    container: AppContainer,
    sessionId: String,
    justFinished: Boolean,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val syncOn by rememberSyncConfigured(container)
    val loaded by produceState<Result<LoadedSession?>?>(null, sessionId) {
        value = runCatching { container.sessions.load(sessionId) }
    }
    val result = loaded
    if (result == null) {
        Box(modifier.fillMaxSize().background(Pulse.bg))
        return
    }
    val session = result.getOrNull()
    if (session == null) {
        // Deleted meanwhile, or unreadable: nothing to show.
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val detail =
        remember(session, syncOn) {
            // Right after a session, "not synced yet" is normal (the upload is starting): only a
            // rejection is worth showing there.
            val sync = syncBadge(session.syncState, syncOn).takeUnless { justFinished && it == SyncBadge.WAITING }
            sessionDetail(session.result, session.entity.startedAtEpochMs, zone, simulated = session.simulated, complete = session.complete, sync = sync)
        }
    SessionDetailContent(detail, modifier, justFinished = justFinished, onBack = onBack, onDone = onBack, onDelete = onDelete)
}

/** Whether sync is set up (URL + token): without it, no sync marks are shown anywhere. */
@Composable
fun rememberSyncConfigured(container: AppContainer): State<Boolean> =
    remember { container.settings.settings.map { it.syncConfigured() } }.collectAsStateWithLifecycle(initialValue = false)
