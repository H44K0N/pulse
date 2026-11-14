package dev.tischbein.pulse.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tischbein.pulse.AppContainer
import dev.tischbein.pulse.coaching.SessionMode
import dev.tischbein.pulse.sensor.ConnectionState
import dev.tischbein.pulse.sensor.FoundDevice
import dev.tischbein.pulse.session.SessionService
import dev.tischbein.pulse.ui.theme.InterText
import dev.tischbein.pulse.ui.theme.Pulse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Train wired to the strap, DataStore and the session service. */
@Composable
fun TrainScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val device by container.sensors.status.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    var mode by rememberSaveable { mutableStateOf(SessionMode.INTERVAL_4X4) }
    var scanning by remember { mutableStateOf(false) }
    var notificationsOff by remember { mutableStateOf(!hasNotificationPermission(context)) }
    LifecycleResumeEffect(Unit) {
        notificationsOff = !hasNotificationPermission(context)
        onPauseOrDispose { }
    }
    val s = settings ?: return

    TrainContent(
        s,
        device,
        mode,
        modifier,
        notificationsOff = notificationsOff,
        onMode = { mode = it },
        onStrap = {
            when {
                device.simulatorProfile != null -> Unit
                device.deviceId == null -> scanning = true
                device.connection == ConnectionState.DISCONNECTED -> container.appScope.launch { container.sensors.autoConnect() }
                else -> Unit
            }
        },
        // The easy target on Train IS the stored default: one value, remembered for next time.
        onEasyTargetMin = { min -> container.appScope.launch { container.settings.save(s.copy(easyTargetMin = min)) } },
        onFixNotifications = { openAppSettings(context) },
        onStart = { SessionService.start(context, mode) },
    )
    if (scanning) ScanDialog(container, onDismiss = { scanning = false })
}

/** Lists H10 straps found while the dialog is open; tapping one remembers and connects it. */
@Composable
fun ScanDialog(
    container: AppContainer,
    onDismiss: () -> Unit,
) {
    val found = remember { mutableStateListOf<FoundDevice>() }
    var error by remember { mutableStateOf<String?>(null) }
    // The scan runs while this effect is active, i.e. while the dialog is shown.
    LaunchedEffect(Unit) {
        try {
            container.sensors.scan().collect { device ->
                if (found.none { it.deviceId == device.deviceId }) found += device
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Scan failed: ${e.message} (is Bluetooth on?)"
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Pulse.surfaceRaised,
        titleContentColor = Pulse.text,
        textContentColor = Pulse.textMid,
        title = { Text("Looking for your H10") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Wear the strap: it only wakes up with skin contact. A strap that is already connected does not show up here.", fontFamily = InterText, fontSize = 14.sp)
                error?.let { Text(it, color = Pulse.danger) }
                found.forEach { device ->
                    Text(
                        "${device.name}  (signal ${device.rssi} dBm)",
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                container.appScope.launch { container.sensors.choose(device) }
                                onDismiss()
                            }.padding(vertical = 10.dp),
                        fontFamily = InterText,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = Pulse.text,
                    )
                }
                if (found.isEmpty() && error == null) Text("Searching…", fontFamily = InterText, color = Pulse.textLow)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Pulse.accent) } },
    )
}
