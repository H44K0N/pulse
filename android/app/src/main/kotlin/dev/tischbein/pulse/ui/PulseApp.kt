package dev.tischbein.pulse.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tischbein.pulse.AppContainer
import dev.tischbein.pulse.ui.design.Destination
import dev.tischbein.pulse.ui.theme.Pulse
import kotlinx.coroutines.launch

/**
 * Top-level navigation. Deliberately no navigation library: three tabs (Train | Progress |
 * Settings) plus two secondary screens (all sessions, session detail) kept on a small back
 * stack of strings, which survives rotation and process restore via rememberSaveable.
 *
 * Precedence: permissions, then a running session always shows the live screen, then a
 * session that just finished shows its summary, then the back stack, then the tab.
 *
 * Deleting a session: it disappears at once and a snackbar offers Undo; only when the
 * snackbar goes away without Undo is it deleted from the database (no confirm dialog).
 */
@Composable
fun PulseApp(container: AppContainer) {
    val context = LocalContext.current
    var bluetoothGranted by remember { mutableStateOf(hasBluetoothPermissions(context)) }
    // Re-check on every resume: the user may have changed permissions in system Settings.
    LifecycleResumeEffect(Unit) {
        bluetoothGranted = hasBluetoothPermissions(context)
        onPauseOrDispose { }
    }
    val active by container.activeSession.collectAsStateWithLifecycle()
    val finishedId by container.finishedSessionId.collectAsStateWithLifecycle()
    val device by container.sensors.status.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Destination.TRAIN) }
    val stack = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) { mutableStateListOf<String>() }
    val hidden = remember { mutableStateListOf<String>() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun requestDelete(id: String) {
        hidden += id
        scope.launch {
            val r = snackbar.showSnackbar("Session deleted", actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (r != SnackbarResult.ActionPerformed) container.sessions.delete(id)
            hidden -= id
        }
    }

    BackHandler(enabled = active == null && finishedId == null && stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = active == null && finishedId != null) { container.finishedSessionId.value = null }
    BackHandler(enabled = active == null && finishedId == null && stack.isEmpty() && tab != Destination.TRAIN) { tab = Destination.TRAIN }

    Scaffold(
        containerColor = Pulse.bg,
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                Snackbar(data, containerColor = Pulse.surfaceRaised, contentColor = Pulse.text, actionColor = Pulse.accent)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().background(Pulse.bg).padding(padding)) {
            val session = active
            val justFinished = finishedId
            val top = stack.lastOrNull()
            when {
                !bluetoothGranted ->
                    PermissionsScreen(onGranted = {
                        bluetoothGranted = true
                        container.appScope.launch { container.sensors.autoConnect() }
                    })
                session != null -> LiveScreen(session, device, container.clock)
                justFinished != null ->
                    SessionDetailScreen(
                        container,
                        justFinished,
                        justFinished = true,
                        onBack = { container.finishedSessionId.value = null },
                        onDelete = {
                            container.finishedSessionId.value = null
                            requestDelete(justFinished)
                        },
                    )
                top == ALL_SESSIONS ->
                    AllSessionsScreen(container, hidden.toSet(), onBack = { stack.removeAt(stack.lastIndex) }, onOpenSession = { stack += DETAIL + it })
                top != null && top.startsWith(DETAIL) -> {
                    val id = top.removePrefix(DETAIL)
                    SessionDetailScreen(
                        container,
                        id,
                        justFinished = false,
                        onBack = { stack.removeAt(stack.lastIndex) },
                        onDelete = {
                            stack.removeAt(stack.lastIndex)
                            requestDelete(id)
                        },
                    )
                }
                else ->
                    MainFrame(tab, onSelect = { tab = it }) { m ->
                        when (tab) {
                            Destination.TRAIN -> TrainScreen(container, m)
                            Destination.PROGRESS ->
                                ProgressScreen(container, hidden.toSet(), m, onShowAll = { stack += ALL_SESSIONS }, onOpenSession = { stack += DETAIL + it })
                            Destination.SETTINGS -> SettingsScreen(container, m)
                        }
                    }
            }
        }
    }
}

private const val ALL_SESSIONS = "all"
private const val DETAIL = "detail:"
