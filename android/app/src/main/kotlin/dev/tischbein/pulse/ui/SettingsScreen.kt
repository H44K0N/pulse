package dev.tischbein.pulse.ui

import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tischbein.pulse.AppConstants
import dev.tischbein.pulse.AppContainer
import dev.tischbein.pulse.data.db.SyncOverview
import dev.tischbein.pulse.data.settings.AppSettings
import dev.tischbein.pulse.data.sync.ConnectionCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * Settings wired to DataStore with autosave: edits show at once, and are written
 * [AppConstants.SETTINGS_AUTOSAVE_DEBOUNCE_MS] after the last change (one write per burst of
 * taps), or right away when the screen is left. Invalid input (only the server URL can be)
 * is shown and not saved until fixed.
 *
 * Sync: the status comes from Room (SessionRepository.observeSyncOverview); "Test connection"
 * runs SyncClient.check on the values in the fields; "Sync now" goes through WorkManager.
 *
 * Only the pending edit lives here; otherwise the screen shows what is stored, so a value
 * changed elsewhere meanwhile (HRmax learned by a session) is not overwritten by a stale copy.
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    val stored by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    var edit by remember { mutableStateOf<AppSettings?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val overview by remember { container.sessions.observeSyncOverview() }.collectAsStateWithLifecycle(initialValue = SyncOverview())
    var check by remember { mutableStateOf<ConnectionCheck?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val shown = edit ?: stored ?: return
    // The last test result is about the URL/token it was run with: forget it when they change.
    LaunchedEffect(shown.serverUrl, shown.apiToken) { check = null }
    // Configured = what is saved (the uploader uses that), not what is half-typed.
    val syncOn = stored?.syncConfigured() == true
    val zone = remember { ZoneId.systemDefault() }
    val sync = remember(syncOn, overview) { syncPanel(syncOn, overview, System.currentTimeMillis(), zone) }

    fun save(s: AppSettings) {
        container.appScope.launch {
            val before = container.settings.current()
            container.settings.save(s)
            // Simulator switched on/off: connect or release the strap accordingly.
            if (before.simulatorEnabled != s.simulatorEnabled) container.sensors.autoConnect()
        }
    }

    LaunchedEffect(edit) {
        val pending = edit ?: return@LaunchedEffect
        delay(AppConstants.SETTINGS_AUTOSAVE_DEBOUNCE_MS)
        if (pending.validationErrors().isEmpty()) {
            save(pending)
            edit = null
        }
    }
    // Leaving the screen within the debounce window still saves.
    val latestEdit by rememberUpdatedState(edit)
    DisposableEffect(Unit) {
        onDispose { latestEdit?.takeIf { it.validationErrors().isEmpty() }?.let { save(it) } }
    }

    val context = LocalContext.current
    val powerManager = context.getSystemService(PowerManager::class.java)
    var optimised by remember { mutableStateOf(!powerManager.isIgnoringBatteryOptimizations(context.packageName)) }
    LifecycleResumeEffect(Unit) {
        optimised = !powerManager.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }

    SettingsContent(
        shown,
        modifier,
        batteryOptimised = optimised,
        sync = sync,
        connectionCheck = check,
        checking = checking,
        onChange = { edit = it },
        onFindStrap = { scanning = true },
        onForgetStrap = { container.appScope.launch { container.sensors.forget() } },
        // Android's battery optimisation (Doze, vendor "app killers") can still interfere with
        // a long session even though it runs as a foreground service. Excluding Pulse is the
        // user's choice: this opens the system list (asking directly would need a permission
        // Google restricts; the list needs none).
        onFixBattery = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
        // Tests what is in the fields right now (even before it is saved). Only on this tap is
        // the token sent anywhere outside the normal upload.
        onTestConnection = {
            checking = true
            scope.launch {
                check = withContext(Dispatchers.IO) { container.syncClient.check(shown.serverUrl, shown.apiToken) }
                checking = false
            }
        },
        onSyncNow = { container.syncNow() },
    )
    if (scanning) ScanDialog(container, onDismiss = { scanning = false })
}
