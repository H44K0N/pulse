package dev.tischbein.pulse.ui

import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.tischbein.pulse.AppContainer
import dev.tischbein.pulse.coaching.estimateHrMax
import dev.tischbein.pulse.data.settings.AppSettings
import dev.tischbein.pulse.data.settings.ZoneSetting
import dev.tischbein.pulse.sensor.sim.SimProfile
import kotlinx.coroutines.launch

/** Typed but not a whole number: fails validation instead of silently becoming a default. */
private const val INVALID = -1

@Composable
fun SettingsScreen(
    container: AppContainer,
    onDone: () -> Unit,
) {
    var draft by remember { mutableStateOf<AppSettings?>(null) }
    var errors by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(Unit) { draft = container.settings.current() }
    val current = draft
    if (current == null) {
        Text("Loading...", Modifier.padding(16.dp))
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Section("Heart rate")
        IntField("Age", current.age, Modifier.fillMaxWidth()) { draft = current.copy(age = it ?: INVALID) }
        val hrMaxSource =
            when {
                current.hrMaxOverride == null -> "estimated: 208 - 0.7 x age = ${estimateHrMax(current.age)}"
                current.hrMaxLearned -> "learned from a session"
                else -> "set manually"
            }
        Text("HRmax in use: ${current.hrMax} ($hrMaxSource)")
        IntField("HRmax override (empty = estimate from age)", current.hrMaxOverride, Modifier.fillMaxWidth(), allowEmpty = true) {
            // Typing a value means "I know my HRmax": manual, not learned.
            draft = current.copy(hrMaxOverride = it, hrMaxLearned = false)
        }

        Section("4x4 protocol (minutes)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IntField("Warmup", current.warmupMin, Modifier.weight(1f)) { draft = current.copy(warmupMin = it ?: INVALID) }
            IntField("Cooldown", current.cooldownMin, Modifier.weight(1f)) { draft = current.copy(cooldownMin = it ?: INVALID) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IntField("Work", current.workMin, Modifier.weight(1f)) { draft = current.copy(workMin = it ?: INVALID) }
            IntField("Recovery", current.recoveryMin, Modifier.weight(1f)) { draft = current.copy(recoveryMin = it ?: INVALID) }
            IntField("Intervals", current.intervalCount, Modifier.weight(1f)) { draft = current.copy(intervalCount = it ?: INVALID) }
        }

        Section("Zones (% of HRmax)")
        ZoneFields("Warmup", current.warmupZone) { draft = current.copy(warmupZone = it) }
        ZoneFields("Work", current.workZone) { draft = current.copy(workZone = it) }
        ZoneFields("Recovery", current.recoveryZone) { draft = current.copy(recoveryZone = it) }
        ZoneFields("Easy", current.easyZone) { draft = current.copy(easyZone = it) }
        IntField("Easy target duration, min (0 = open-ended)", current.easyTargetMin, Modifier.fillMaxWidth()) {
            draft = current.copy(easyTargetMin = it ?: INVALID)
        }

        Section("Sync server (used from Milestone 5)")
        OutlinedTextField(
            value = current.serverUrl,
            onValueChange = { draft = current.copy(serverUrl = it.trim()) },
            label = { Text("Server URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = current.apiToken,
            onValueChange = { draft = current.copy(apiToken = it.trim()) },
            label = { Text("API token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        Section("Debug: simulator")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Use simulated heart rate instead of the H10", Modifier.weight(1f))
            Switch(checked = current.simulatorEnabled, onCheckedChange = { draft = current.copy(simulatorEnabled = it) })
        }
        if (current.simulatorEnabled) {
            SimProfile.entries.forEach { profile ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = current.simulatorProfile == profile) {
                        draft = current.copy(simulatorProfile = profile)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = current.simulatorProfile == profile, onClick = null)
                    Text(profile.name, Modifier.padding(start = 8.dp))
                }
            }
        }

        Section("Battery")
        BatteryOptimizationRow()

        HorizontalDivider()
        errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                errors = current.validationErrors()
                if (errors.isEmpty()) {
                    container.appScope.launch {
                        container.settings.save(current)
                        // Simulator switched on/off: connect or release the strap accordingly.
                        container.sensors.autoConnect()
                    }
                    onDone()
                }
            }) { Text("Save") }
            OutlinedButton(onClick = onDone) { Text("Cancel") }
        }
    }
}

/**
 * Android's battery optimisation (Doze, and vendor "app killers" on Samsung/Xiaomi/...) can
 * still interfere with a long session even though it runs as a foreground service. Excluding
 * Pulse is the user's choice: this opens the system list where they can do it. (Asking
 * directly with ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS would need an extra permission
 * that Google Play restricts; the list needs none.)
 */
@Composable
private fun BatteryOptimizationRow() {
    val context = LocalContext.current
    val powerManager = context.getSystemService(PowerManager::class.java)
    var ignoring by remember { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName)) }
    LifecycleResumeEffect(Unit) {
        ignoring = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }
    Text(
        if (ignoring) {
            "Battery optimisation: off for Pulse (best for long sessions)."
        } else {
            "Battery optimisation: on. If sessions stop with the screen off, set Pulse to \"Not optimised\" / \"Unrestricted\"."
        },
    )
    if (!ignoring) {
        OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }) { Text("Open battery optimisation settings") }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun ZoneFields(
    label: String,
    zone: ZoneSetting,
    onChange: (ZoneSetting) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(80.dp))
        IntField("Low %", zone.lowPct, Modifier.weight(1f)) { onChange(zone.copy(lowPct = it ?: INVALID)) }
        IntField("High %", zone.highPct, Modifier.weight(1f)) { onChange(zone.copy(highPct = it ?: INVALID)) }
    }
}

/**
 * A number field. Keeps its own text so half-typed input ("", "1") is not overwritten;
 * reports null when the text is not a whole number (or is empty).
 */
@Composable
private fun IntField(
    label: String,
    value: Int?,
    modifier: Modifier = Modifier,
    allowEmpty: Boolean = false,
    onValue: (Int?) -> Unit,
) {
    var text by remember { mutableStateOf(value?.takeIf { it != INVALID }?.toString() ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it.trim()
            onValue(text.toIntOrNull() ?: if (allowEmpty && text.isEmpty()) null else INVALID)
        },
        label = { Text(label) },
        singleLine = true,
        isError = text.toIntOrNull() == null && !(allowEmpty && text.isEmpty()),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
