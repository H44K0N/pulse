package dev.tischbein.pulse.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tischbein.pulse.coaching.MS_PER_MINUTE
import dev.tischbein.pulse.coaching.SessionMode
import dev.tischbein.pulse.data.settings.AppSettings
import dev.tischbein.pulse.data.settings.ZoneSetting
import dev.tischbein.pulse.sensor.ConnectionState
import dev.tischbein.pulse.sensor.DeviceStatus
import dev.tischbein.pulse.ui.design.BottomBar
import dev.tischbein.pulse.ui.design.Destination
import dev.tischbein.pulse.ui.design.Icon
import dev.tischbein.pulse.ui.design.Overline
import dev.tischbein.pulse.ui.design.PhaseTimeline
import dev.tischbein.pulse.ui.design.PillButton
import dev.tischbein.pulse.ui.design.PulseCard
import dev.tischbein.pulse.ui.design.PulseIcon
import dev.tischbein.pulse.ui.design.RowDivider
import dev.tischbein.pulse.ui.design.ScreenPadding
import dev.tischbein.pulse.ui.design.SectionTitle
import dev.tischbein.pulse.ui.design.Segmented
import dev.tischbein.pulse.ui.design.SettingRow
import dev.tischbein.pulse.ui.design.StatusPill
import dev.tischbein.pulse.ui.design.Stepper
import dev.tischbein.pulse.ui.design.Tag
import dev.tischbein.pulse.ui.design.ValueUnit
import dev.tischbein.pulse.ui.theme.InterDisplay
import dev.tischbein.pulse.ui.theme.InterText
import dev.tischbein.pulse.ui.theme.Pulse
import dev.tischbein.pulse.ui.theme.PulseType

/** The app's frame for the main destinations: content above a slim bottom bar. */
@Composable
fun MainFrame(
    selected: Destination,
    modifier: Modifier = Modifier,
    onSelect: (Destination) -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    Column(modifier.fillMaxSize().background(Pulse.bg)) {
        content(Modifier.weight(1f))
        RowDivider()
        BottomBar(selected, onSelect = onSelect)
    }
}

/**
 * Train (home) screen, stateless: the strap, the mode, what the session will look like, and
 * one big Start.
 */
@Composable
fun TrainContent(
    settings: AppSettings,
    device: DeviceStatus,
    mode: SessionMode,
    todayText: String,
    lastSession: SessionListRow?,
    modifier: Modifier = Modifier,
    onMode: (SessionMode) -> Unit = {},
    onStart: () -> Unit = {},
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Overline(todayText)
        Text("Ready to train", fontFamily = InterDisplay, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = Pulse.text)

        StrapCard(device)

        Segmented(listOf("4×4 intervals", "Easy run"), if (mode == SessionMode.INTERVAL_4X4) 0 else 1, Modifier.fillMaxWidth()) {
            onMode(if (it == 0) SessionMode.INTERVAL_4X4 else SessionMode.EASY)
        }

        if (mode == SessionMode.INTERVAL_4X4) FourByFourPlan(settings) else EasyPlan(settings)

        PillButton("Start", Modifier.fillMaxWidth(), height = 68.dp, subtitle = if (mode == SessionMode.INTERVAL_4X4) "voice coach on" else null, onClick = onStart)

        lastSession?.let {
            SectionTitle("Last session")
            SessionRowView(it)
        }
    }
}

@Composable
private fun StrapCard(d: DeviceStatus) {
    PulseCard(padding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Pulse.danger.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(PulseIcon.HEART, Pulse.danger, size = 22.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (d.simulatorProfile != null) "Simulator" else "Polar H10", fontFamily = InterText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Pulse.text)
                Text(d.simulatorProfile?.name ?: d.deviceId ?: "No strap chosen", style = PulseType.smallNumber.copy(fontSize = 12.sp), color = Pulse.textLow)
            }
            val (label, dot) =
                when (d.connection) {
                    ConnectionState.CONNECTED -> "Connected" to Pulse.inZone.accent
                    ConnectionState.CONNECTING -> "Connecting" to Pulse.warning
                    ConnectionState.DISCONNECTED -> "Off" to Pulse.textLow
                }
            StatusPill(label, dot)
        }
        if (d.connection == ConnectionState.CONNECTED && d.simulatorProfile == null) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(PulseIcon.BATTERY, Pulse.textMid, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(d.batteryPercent?.let { "$it %" } ?: "–", style = PulseType.smallNumber.copy(fontSize = 13.sp), color = Pulse.textMid)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(PulseIcon.SIGNAL, if (d.skinContact == true) Pulse.inZone.accent else Pulse.textMid, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when (d.skinContact) {
                            true -> "Skin contact"
                            false -> "No contact: moisten electrodes"
                            null -> "Contact –"
                        },
                        fontFamily = InterText,
                        fontSize = 13.sp,
                        color = Pulse.textMid,
                    )
                }
            }
        }
    }
}

private fun minutes(ms: Long) = (ms / MS_PER_MINUTE).toInt()

@Composable
private fun FourByFourPlan(s: AppSettings) {
    val cfg = s.toCoachingConfig().fourByFour
    val total = minutes(cfg.warmupMs + cfg.intervalCount * (cfg.workMs + cfg.recoveryMs) + cfg.cooldownMs)
    PulseCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ValueUnit("$total", "min", PulseType.bigNumber, Pulse.text, unitSize = 15.sp)
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Overline("Work zone")
                Text(
                    "${cfg.workZone.lowBpm(s.hrMax)}–${cfg.workZone.highBpm(s.hrMax)} bpm",
                    style = PulseType.number.copy(fontSize = 20.sp),
                    color = Pulse.inZone.accent,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        PhaseTimeline(planSegments(cfg), 0f, Modifier.fillMaxWidth().height(16.dp), accent = Pulse.textMid)
        Spacer(Modifier.height(10.dp))
        Row {
            Text("Warmup ${minutes(cfg.warmupMs)}", fontFamily = InterText, fontSize = 12.sp, color = Pulse.textLow, modifier = Modifier.weight(1f))
            Text("${cfg.intervalCount} × ${minutes(cfg.workMs)} min work · ${minutes(cfg.recoveryMs)} min easy", fontFamily = InterText, fontSize = 12.sp, color = Pulse.textMid)
            Text("Cool ${minutes(cfg.cooldownMs)}", fontFamily = InterText, fontSize = 12.sp, color = Pulse.textLow, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Spacer(Modifier.height(14.dp))
        RowDivider()
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Zones from HRmax ", fontFamily = InterText, fontSize = 13.sp, color = Pulse.textMid)
            Text("${s.hrMax}", style = PulseType.smallNumber.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = Pulse.text)
            Spacer(Modifier.width(8.dp))
            Tag(if (s.hrMaxLearned) "learned" else if (s.hrMaxOverride != null) "manual" else "estimated")
        }
    }
}

@Composable
private fun EasyPlan(s: AppSettings) {
    val zone = s.easyZone.toZonePercent()
    PulseCard {
        SettingRow("Target duration", hint = "Halfway drift report when set") {
            Stepper(if (s.easyTargetMin == 0) "open" else "${s.easyTargetMin}", unit = if (s.easyTargetMin == 0) null else "min")
        }
        RowDivider()
        SettingRow("Easy zone", hint = "${s.easyZone.lowPct}–${s.easyZone.highPct} % of HRmax ${s.hrMax}") {
            Text("${zone.lowBpm(s.hrMax)}–${zone.highBpm(s.hrMax)}", style = PulseType.number.copy(fontSize = 20.sp), color = Pulse.below.accent)
        }
    }
}

/**
 * Settings, stateless and grouped: steppers for minutes and percentages instead of free text
 * fields, and every zone shown both as % of HRmax and in bpm.
 */
@Composable
fun SettingsContent(
    s: AppSettings,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
) {
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
            SettingRow("Age", hint = "Estimate: 208 − 0.7 × age") { Stepper("${s.age}", unit = "y") }
            RowDivider()
            SettingRow("HRmax", hint = if (s.hrMaxLearned) "Learned from a session · tap to set" else "Tap to set manually") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s.hrMaxLearned) Tag("learned", color = Pulse.accent)
                    Spacer(Modifier.width(10.dp))
                    ValueUnit("${s.hrMax}", "bpm", PulseType.number.copy(fontSize = 22.sp), Pulse.text, unitSize = 12.sp)
                }
            }
        }

        SectionTitle("4×4 protocol")
        PulseCard(padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
            SettingRow("Warmup") { Stepper("${s.warmupMin}", unit = "min") }
            RowDivider()
            SettingRow("Work interval") { Stepper("${s.workMin}", unit = "min") }
            RowDivider()
            SettingRow("Recovery") { Stepper("${s.recoveryMin}", unit = "min") }
            RowDivider()
            SettingRow("Intervals") { Stepper("${s.intervalCount}", unit = "×") }
            RowDivider()
            SettingRow("Cooldown") { Stepper("${s.cooldownMin}", unit = "min") }
        }

        SectionTitle("Zones", trailing = "% of HRmax ${s.hrMax}")
        PulseCard(padding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)) {
            ZoneRow("Work", s.workZone, s.hrMax, Pulse.inZone.accent)
            RowDivider()
            ZoneRow("Warmup", s.warmupZone, s.hrMax, Pulse.below.accent)
            RowDivider()
            ZoneRow("Recovery", s.recoveryZone, s.hrMax, Pulse.below.accent)
            RowDivider()
            ZoneRow("Easy run", s.easyZone, s.hrMax, Pulse.below.accent)
        }

        SectionTitle("Sensor")
        PulseCard(padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
            SettingRow("Polar H10", hint = s.deviceId ?: "None chosen") { Text("Forget", fontFamily = InterText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Pulse.danger) }
            RowDivider()
            SettingRow("Simulator", hint = "Test without the strap") {
                Switch(
                    checked = s.simulatorEnabled,
                    onCheckedChange = {},
                    colors = SwitchDefaults.colors(checkedTrackColor = Pulse.accent, uncheckedTrackColor = Pulse.surfaceRaised, uncheckedBorderColor = Pulse.stroke, uncheckedThumbColor = Pulse.textMid),
                )
            }
        }

        SectionTitle("Sync & battery")
        PulseCard(padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
            SettingRow("Server", hint = s.serverUrl.ifBlank { "Not set (arrives in a later version)" }) { Chevron() }
            RowDivider()
            SettingRow("Battery optimisation", hint = "Let sessions run with the screen off") { Chevron() }
        }
    }
}

@Composable
private fun Chevron() {
    Text("›", fontFamily = InterText, fontSize = 22.sp, color = Pulse.textLow)
}

/** A zone as a range bar on a 50-100 % scale, with % and bpm. */
@Composable
private fun ZoneRow(
    label: String,
    z: ZoneSetting,
    hrMax: Int,
    color: Color,
) {
    val zp = z.toZonePercent()
    Column(Modifier.padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontFamily = InterText, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = Pulse.text, modifier = Modifier.weight(1f))
            Text("${z.lowPct}–${z.highPct} %", style = PulseType.smallNumber.copy(fontSize = 13.sp), color = Pulse.textMid)
            Spacer(Modifier.width(10.dp))
            ValueUnit("${zp.lowBpm(hrMax)}–${zp.highBpm(hrMax)}", "bpm", PulseType.smallNumber.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color, unitSize = 11.sp, unitColor = Pulse.textLow)
        }
        Spacer(Modifier.height(10.dp))
        // 50..100 % scale; handles at both ends of the range.
        Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Pulse.surfaceRaised))
            val start = ((z.lowPct - 50) / 50f).coerceIn(0f, 1f)
            val end = ((z.highPct - 50) / 50f).coerceIn(0f, 1f)
            Row(Modifier.fillMaxWidth()) {
                if (start > 0f) Spacer(Modifier.weight(start))
                Box(Modifier.weight((end - start).coerceAtLeast(0.01f)).height(18.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(color))
                    Box(Modifier.align(Alignment.CenterStart).size(18.dp).clip(CircleShape).background(Pulse.text))
                    Box(Modifier.align(Alignment.CenterEnd).size(18.dp).clip(CircleShape).background(Pulse.text))
                }
                if (end < 1f) Spacer(Modifier.weight(1f - end))
            }
        }
    }
}
