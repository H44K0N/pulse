package dev.tischbein.pulse.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tischbein.pulse.coaching.estimateHrMax
import dev.tischbein.pulse.data.settings.AppSettings
import dev.tischbein.pulse.data.settings.ZoneSetting
import dev.tischbein.pulse.sensor.sim.SimProfile
import dev.tischbein.pulse.ui.design.CardShape
import dev.tischbein.pulse.ui.design.PulseCard
import dev.tischbein.pulse.ui.design.RowDivider
import dev.tischbein.pulse.ui.design.ScreenPadding
import dev.tischbein.pulse.ui.design.SectionTitle
import dev.tischbein.pulse.ui.design.SettingRow
import dev.tischbein.pulse.ui.design.Stepper
import dev.tischbein.pulse.ui.design.Tag
import dev.tischbein.pulse.ui.design.ValueUnit
import dev.tischbein.pulse.ui.theme.InterDisplay
import dev.tischbein.pulse.ui.theme.InterText
import dev.tischbein.pulse.ui.theme.Pulse
import dev.tischbein.pulse.ui.theme.PulseType
import kotlin.math.roundToInt

/** The collapsed groups of Settings. */
enum class SettingsSection { WORKOUT, SYNC, DEVELOPER }

/**
 * Settings, stateless. Every control reports the whole new [AppSettings] through [onChange];
 * the caller saves it (debounced, see SettingsScreen). Steppers and sliders stop at the
 * limits in AppSettings, so most values cannot be invalid; the server URL is checked as typed.
 *
 * Most used on top (heart rate, strap); the rest in collapsed groups: Workout (protocol and
 * zones), Sync (server), Developer (simulator).
 */
@Composable
fun SettingsContent(
    s: AppSettings,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    batteryOptimised: Boolean = false,
    initialOpen: Set<SettingsSection> = emptySet(),
    onChange: (AppSettings) -> Unit = {},
    onFindStrap: () -> Unit = {},
    onForgetStrap: () -> Unit = {},
    onFixBattery: () -> Unit = {},
) {
    var open by rememberSaveable { mutableStateOf(initialOpen.map { it.name }.toSet()) }
    fun toggle(section: SettingsSection) {
        open = if (section.name in open) open - section.name else open + section.name
    }
    Column(
        modifier
            .then(if (scrollable) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = ScreenPadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Settings", fontFamily = InterDisplay, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = Pulse.text)
        Text("Changes save automatically", fontFamily = InterText, fontSize = 13.sp, color = Pulse.textLow)

        SectionTitle("Heart rate")
        PulseCard(padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
            SettingRow("Age", hint = "HRmax estimate ${estimateHrMax(s.age)}") {
                Stepper(
                    "${s.age}",
                    unit = "y",
                    onMinus = { onChange(s.copy(age = (s.age - 1).coerceIn(AppSettings.AGE_RANGE))) },
                    onPlus = { onChange(s.copy(age = (s.age + 1).coerceIn(AppSettings.AGE_RANGE))) },
                )
            }
            RowDivider()
            SettingRow(
                "HRmax",
                hint =
                    when (hrMaxSource(s)) {
                        "learned" -> "Learned from a session"
                        "manual" -> "Set manually"
                        else -> "Estimated from your age"
                    },
            ) {
                // Changing it by hand means "I know my HRmax": manual, not learned.
                Stepper(
                    "${s.hrMax}",
                    unit = "bpm",
                    onMinus = { onChange(s.copy(hrMaxOverride = (s.hrMax - 1).coerceIn(AppSettings.HR_MAX_RANGE), hrMaxLearned = false)) },
                    onPlus = { onChange(s.copy(hrMaxOverride = (s.hrMax + 1).coerceIn(AppSettings.HR_MAX_RANGE), hrMaxLearned = false)) },
                )
            }
            if (s.hrMaxOverride != null) {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (s.hrMaxLearned) Tag("learned", color = Pulse.accent)
                    Spacer(Modifier.weight(1f))
                    LinkText("Use age estimate (${estimateHrMax(s.age)})") { onChange(s.copy(hrMaxOverride = null, hrMaxLearned = false)) }
                }
            }
        }

        SectionTitle("Strap")
        PulseCard(padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
            SettingRow("Polar H10", hint = s.deviceId ?: "None chosen") {
                LinkText(if (s.deviceId == null) "Find" else "Change", onClick = onFindStrap)
                if (s.deviceId != null) {
                    Spacer(Modifier.width(16.dp))
                    LinkText("Forget", Pulse.danger, onForgetStrap)
                }
            }
        }
        if (batteryOptimised) {
            Notice("Battery optimisation is on: sessions may stop when the screen is off.", "Fix", onFixBattery)
        }

        Collapsible("Workout", "4×4 protocol, zones", SettingsSection.WORKOUT.name in open, { toggle(SettingsSection.WORKOUT) }) {
            WorkoutSettings(s, onChange)
        }
        Collapsible("Sync", if (s.serverUrl.isEmpty()) "Off" else s.serverUrl, SettingsSection.SYNC.name in open, { toggle(SettingsSection.SYNC) }) {
            SyncSettings(s, onChange)
        }
        Collapsible(
            "Developer",
            if (s.simulatorEnabled) "Simulator on · ${s.simulatorProfile.name}" else "Simulator off",
            SettingsSection.DEVELOPER.name in open,
            { toggle(SettingsSection.DEVELOPER) },
        ) {
            DeveloperSettings(s, onChange)
        }
    }
}

@Composable
private fun LinkText(
    text: String,
    color: Color = Pulse.accent,
    onClick: () -> Unit,
) {
    Text(
        text,
        fontFamily = InterText,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        color = color,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

/** A group header that opens and closes its card. */
@Composable
private fun Collapsible(
    title: String,
    summary: String,
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clip(CardShape).background(Pulse.surface)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = if (open) "Close $title" else "Open $title", onClick = onToggle).padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontFamily = InterText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Pulse.text)
                Text(summary, fontFamily = InterText, fontSize = 12.sp, color = Pulse.textLow, maxLines = 1)
            }
            Text(if (open) "▴" else "▾", fontSize = 18.sp, color = Pulse.textMid)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 10.dp)) {
                RowDivider()
                content()
            }
        }
    }
}

@Composable
private fun MinutesRow(
    label: String,
    value: Int,
    range: IntRange,
    unit: String = "min",
    onValue: (Int) -> Unit,
) {
    SettingRow(label) {
        Stepper("$value", unit = unit, onMinus = { onValue((value - 1).coerceIn(range)) }, onPlus = { onValue((value + 1).coerceIn(range)) })
    }
}

@Composable
private fun WorkoutSettings(
    s: AppSettings,
    onChange: (AppSettings) -> Unit,
) {
    Overline4x4("4×4 protocol")
    MinutesRow("Warmup", s.warmupMin, AppSettings.PHASE_MIN_RANGE) { onChange(s.copy(warmupMin = it)) }
    MinutesRow("Work interval", s.workMin, AppSettings.WORK_MIN_RANGE) { onChange(s.copy(workMin = it)) }
    MinutesRow("Recovery", s.recoveryMin, AppSettings.PHASE_MIN_RANGE) { onChange(s.copy(recoveryMin = it)) }
    MinutesRow("Intervals", s.intervalCount, AppSettings.INTERVAL_COUNT_RANGE, unit = "×") { onChange(s.copy(intervalCount = it)) }
    MinutesRow("Cooldown", s.cooldownMin, AppSettings.PHASE_MIN_RANGE) { onChange(s.copy(cooldownMin = it)) }
    RowDivider()
    Overline4x4("Zones · % of HRmax ${s.hrMax}")
    ZoneRow("Work", s.workZone, s.hrMax, Pulse.inZone.accent) { onChange(s.copy(workZone = it)) }
    ZoneRow("Warmup", s.warmupZone, s.hrMax, Pulse.below.accent) { onChange(s.copy(warmupZone = it)) }
    ZoneRow("Recovery", s.recoveryZone, s.hrMax, Pulse.below.accent) { onChange(s.copy(recoveryZone = it)) }
    ZoneRow("Easy run", s.easyZone, s.hrMax, Pulse.below.accent) { onChange(s.copy(easyZone = it)) }
}

@Composable
private fun Overline4x4(text: String) {
    Text(text, fontFamily = InterText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Pulse.textMid, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
}

/** A zone as a range slider on [AppSettings.ZONE_PCT_RANGE], with % and bpm. */
@Composable
private fun ZoneRow(
    label: String,
    z: ZoneSetting,
    hrMax: Int,
    color: Color,
    onZone: (ZoneSetting) -> Unit,
) {
    val zp = z.toZonePercent()
    val range = AppSettings.ZONE_PCT_RANGE
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontFamily = InterText, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = Pulse.text, modifier = Modifier.weight(1f))
            Text("${z.lowPct}–${z.highPct} %", style = PulseType.smallNumber.copy(fontSize = 13.sp), color = Pulse.textMid)
            Spacer(Modifier.width(10.dp))
            ValueUnit("${zp.lowBpm(hrMax)}–${zp.highBpm(hrMax)}", "bpm", PulseType.smallNumber.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color, unitSize = 11.sp, unitColor = Pulse.textLow)
        }
        RangeSlider(
            value = z.lowPct.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat())..z.highPct.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = { r ->
                val lo = r.start.roundToInt()
                val hi = r.endInclusive.roundToInt()
                if (lo < hi) onZone(ZoneSetting(lo, hi))
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
            colors =
                SliderDefaults.colors(
                    thumbColor = Pulse.text,
                    activeTrackColor = color,
                    inactiveTrackColor = Pulse.surfaceRaised,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
            modifier = Modifier.height(32.dp),
        )
    }
}

@Composable
private fun fieldColors() =
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = Pulse.text,
        unfocusedTextColor = Pulse.text,
        focusedBorderColor = Pulse.accent,
        unfocusedBorderColor = Pulse.stroke,
        focusedLabelColor = Pulse.accent,
        unfocusedLabelColor = Pulse.textMid,
        cursorColor = Pulse.accent,
    )

@Composable
private fun SyncSettings(
    s: AppSettings,
    onChange: (AppSettings) -> Unit,
) {
    val urlError = AppSettings.serverUrlError(s.serverUrl)
    Spacer(Modifier.height(12.dp))
    Text(
        "Upload arrives in a later version; until then nothing leaves the phone.",
        fontFamily = InterText,
        fontSize = 12.sp,
        color = Pulse.textLow,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = s.serverUrl,
        onValueChange = { onChange(s.copy(serverUrl = it.trim())) },
        label = { Text("Server URL") },
        placeholder = { Text("https://pulse.example.com") },
        isError = urlError != null,
        supportingText = urlError?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = s.apiToken,
        onValueChange = { onChange(s.copy(apiToken = it.trim())) },
        label = { Text("API token") },
        singleLine = true,
        // Masked: the token is a password for the server.
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun DeveloperSettings(
    s: AppSettings,
    onChange: (AppSettings) -> Unit,
) {
    SettingRow("Simulator", hint = "Simulated heart rate instead of the strap, to test without wearing it") {
        Switch(
            checked = s.simulatorEnabled,
            onCheckedChange = { onChange(s.copy(simulatorEnabled = it)) },
            colors = SwitchDefaults.colors(checkedTrackColor = Pulse.accent, uncheckedTrackColor = Pulse.surfaceRaised, uncheckedBorderColor = Pulse.stroke, uncheckedThumbColor = Pulse.textMid),
        )
    }
    if (s.simulatorEnabled) {
        SimProfile.entries.forEach { profile ->
            val on = s.simulatorProfile == profile
            Row(
                Modifier.fillMaxWidth().clip(CircleShape).clickable { onChange(s.copy(simulatorProfile = profile)) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(18.dp).clip(CircleShape).background(if (on) Pulse.accent else Pulse.surfaceRaised),
                    contentAlignment = Alignment.Center,
                ) {
                    if (on) Box(Modifier.size(7.dp).clip(CircleShape).background(Pulse.bg))
                }
                Spacer(Modifier.width(12.dp))
                Text(profileLabel(profile), fontFamily = InterText, fontSize = 15.sp, color = if (on) Pulse.text else Pulse.textMid)
            }
        }
    }
}

private fun profileLabel(p: SimProfile): String = p.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
