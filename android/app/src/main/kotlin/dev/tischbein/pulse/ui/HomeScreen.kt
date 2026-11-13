package dev.tischbein.pulse.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tischbein.pulse.AppContainer
import dev.tischbein.pulse.coaching.SessionMode
import dev.tischbein.pulse.sensor.ConnectionState
import dev.tischbein.pulse.sensor.DeviceStatus
import dev.tischbein.pulse.sensor.FoundDevice
import dev.tischbein.pulse.session.SessionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val status by container.sensors.status.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    var mode by rememberSaveable { mutableStateOf(SessionMode.INTERVAL_4X4) }
    // Easy target in minutes as typed; null = not touched yet, use the Settings default.
    var easyTargetText by rememberSaveable { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Pulse", style = MaterialTheme.typography.headlineMedium)

        DeviceCard(status)
        if (status.simulatorProfile == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scanning = true }) { Text("Scan / connect H10") }
                if (status.deviceId != null) {
                    if (status.connection == ConnectionState.DISCONNECTED) {
                        OutlinedButton(onClick = { container.appScope.launch { container.sensors.autoConnect() } }) { Text("Connect") }
                    } else {
                        OutlinedButton(onClick = { container.sensors.disconnect() }) { Text("Disconnect") }
                    }
                    TextButton(onClick = { container.appScope.launch { container.sensors.forget() } }) { Text("Forget") }
                }
            }
        }

        Text("Mode", style = MaterialTheme.typography.titleMedium)
        ModeOption("4x4 intervals", mode == SessionMode.INTERVAL_4X4) { mode = SessionMode.INTERVAL_4X4 }
        ModeOption("Easy / long", mode == SessionMode.EASY) { mode = SessionMode.EASY }
        val easyTargetInput = easyTargetText ?: settings?.easyTargetMin?.takeIf { it > 0 }?.toString() ?: ""
        val easyTargetMin = parseEasyTargetMin(easyTargetInput)
        if (mode == SessionMode.EASY) {
            OutlinedTextField(
                value = easyTargetInput,
                onValueChange = { easyTargetText = it.filter(Char::isDigit).take(3) },
                label = { Text("Target duration, minutes (optional)") },
                supportingText = { Text(if (easyTargetMin == null) "Up to $MAX_EASY_TARGET_MIN minutes" else "Empty = open-ended") },
                isError = easyTargetMin == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        settings?.let { s ->
            val source = if (s.hrMaxOverride == null) "estimated from age ${s.age}" else if (s.hrMaxLearned) "learned" else "set manually"
            Text("HRmax ${s.hrMax} ($source)")
        }

        val canStart = status.simulatorProfile != null || status.deviceId != null
        val inputOk = mode != SessionMode.EASY || easyTargetMin != null
        Button(
            onClick = { SessionService.start(context, mode, easyTargetMin.takeIf { mode == SessionMode.EASY }) },
            enabled = canStart && inputOk,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Start")
        }
        if (!canStart) Text("Connect an H10 first, or turn on the simulator in Settings.")

        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("Settings") }

        if (!hasNotificationPermission(context)) {
            Text(
                "Notifications are off, so the session notification (heart rate, Pause/End) will be " +
                    "hidden. You can allow them in the app's system settings.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { openAppSettings(context) }) { Text("Open app settings") }
        }
    }

    if (scanning) {
        ScanDialog(container, onDismiss = { scanning = false })
    }
}

@Composable
private fun DeviceCard(status: DeviceStatus) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (status.simulatorProfile != null) {
                Text("Simulator: ${status.simulatorProfile}", style = MaterialTheme.typography.titleMedium)
                Text("The H10 is ignored while the simulator is on (Settings).")
            } else {
                Text("Polar H10 ${status.deviceId ?: "(none chosen)"}", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (status.connection) {
                        ConnectionState.CONNECTED -> "Connected"
                        ConnectionState.CONNECTING -> "Connecting... (put the strap on: it only wakes up with skin contact)"
                        ConnectionState.DISCONNECTED -> "Disconnected"
                    },
                )
                Text("Battery: ${status.batteryPercent?.let { "$it %" } ?: "unknown"}")
                Text(
                    "Skin contact: " +
                        when (status.skinContact) {
                            true -> "yes"
                            false -> "NO (moisten the electrodes)"
                            null -> "unknown"
                        },
                )
                status.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun ModeOption(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}

/** Lists H10 straps found while the dialog is open; tapping one remembers and connects it. */
@Composable
private fun ScanDialog(
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
        title = { Text("Scanning for Polar H10") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Wear the strap (it only advertises with skin contact). A connected strap does not show up here.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                found.forEach { device ->
                    Text(
                        "${device.name}  (signal ${device.rssi} dBm)",
                        Modifier.fillMaxWidth().clickable {
                            container.appScope.launch { container.sensors.choose(device) }
                            onDismiss()
                        }.padding(vertical = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (found.isEmpty() && error == null) Text("Searching...")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val MAX_EASY_TARGET_MIN = 600

/** "" -> 0 (open-ended), "45" -> 45, out of range -> null (invalid). Same limit as Settings. */
fun parseEasyTargetMin(text: String): Int? {
    if (text.isBlank()) return 0
    return text.trim().toIntOrNull()?.takeIf { it in 0..MAX_EASY_TARGET_MIN }
}
