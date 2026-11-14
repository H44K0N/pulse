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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tischbein.pulse.AppConstants
import dev.tischbein.pulse.coaching.MS_PER_MINUTE
import dev.tischbein.pulse.coaching.SessionMode
import dev.tischbein.pulse.data.settings.AppSettings
import dev.tischbein.pulse.sensor.ConnectionState
import dev.tischbein.pulse.sensor.DeviceStatus
import dev.tischbein.pulse.ui.design.BottomBar
import dev.tischbein.pulse.ui.design.CardShape
import dev.tischbein.pulse.ui.design.Destination
import dev.tischbein.pulse.ui.design.Icon
import dev.tischbein.pulse.ui.design.Overline
import dev.tischbein.pulse.ui.design.PhaseTimeline
import dev.tischbein.pulse.ui.design.PillButton
import dev.tischbein.pulse.ui.design.PulseCard
import dev.tischbein.pulse.ui.design.PulseIcon
import dev.tischbein.pulse.ui.design.RowDivider
import dev.tischbein.pulse.ui.design.ScreenPadding
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
 * Train (home) screen, stateless: the strap (one line; details only when something is
 * wrong), the mode, a one-line plan (tap for details) and one big Start at the bottom.
 */
@Composable
fun TrainContent(
    settings: AppSettings,
    device: DeviceStatus,
    mode: SessionMode,
    modifier: Modifier = Modifier,
    notificationsOff: Boolean = false,
    initialPlanOpen: Boolean = false,
    onMode: (SessionMode) -> Unit = {},
    onStrap: () -> Unit = {},
    onEasyTargetMin: (Int) -> Unit = {},
    onFixNotifications: () -> Unit = {},
    onStart: () -> Unit = {},
) {
    var planOpen by rememberSaveable { mutableStateOf(initialPlanOpen) }
    val canStart = device.simulatorProfile != null || device.deviceId != null
    Column(modifier.fillMaxSize().padding(horizontal = ScreenPadding, vertical = 16.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Train", fontFamily = InterDisplay, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = Pulse.text)
            StrapCard(device, onStrap)
            if (notificationsOff) {
                Notice("Notifications are off, so the session notification (heart rate, Pause/End) is hidden.", "Turn on", onFixNotifications)
            }
            Segmented(listOf("4×4 intervals", "Easy run"), if (mode == SessionMode.INTERVAL_4X4) 0 else 1, Modifier.fillMaxWidth()) {
                onMode(if (it == 0) SessionMode.INTERVAL_4X4 else SessionMode.EASY)
            }
            PulseCard(Modifier.clip(CardShape).clickable(onClickLabel = if (planOpen) "Hide plan details" else "Show plan details") { planOpen = !planOpen }) {
                if (mode == SessionMode.INTERVAL_4X4) FourByFourPlan(settings, planOpen) else EasyPlan(settings, planOpen, onEasyTargetMin)
            }
        }
        Spacer(Modifier.height(14.dp))
        if (!canStart) {
            Text(
                "Connect your strap first (or turn on the simulator in Settings › Developer).",
                fontFamily = InterText,
                fontSize = 13.sp,
                color = Pulse.textLow,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            )
        }
        PillButton("Start", Modifier.fillMaxWidth(), height = 68.dp, enabled = canStart, onClick = onStart)
    }
}

/** A one-line problem with a fix-it link. */
@Composable
fun Notice(
    text: String,
    action: String,
    onAction: () -> Unit,
    color: Color = Pulse.warning,
) {
    Row(
        Modifier.fillMaxWidth().clip(CardShape).background(color.copy(alpha = 0.10f)).clickable(onClick = onAction).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontFamily = InterText, fontSize = 13.sp, color = Pulse.textMid, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text("$action ›", fontFamily = InterText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = color)
    }
}

@Composable
private fun StrapCard(
    d: DeviceStatus,
    onClick: () -> Unit,
) {
    val simulator = d.simulatorProfile != null
    val (label, dot) =
        when {
            simulator -> "Simulator on" to Pulse.warning
            d.deviceId == null -> "Find strap" to Pulse.accent
            d.connection == ConnectionState.CONNECTED -> "Connected" to Pulse.inZone.accent
            d.connection == ConnectionState.CONNECTING -> "Connecting" to Pulse.warning
            else -> "Connect" to Pulse.accent
        }
    val problems =
        if (simulator) {
            emptyList()
        } else {
            listOfNotNull(
                d.problem,
                "Connecting: put the strap on, it wakes up with skin contact".takeIf { d.connection == ConnectionState.CONNECTING },
                "No skin contact: moisten the electrodes".takeIf { d.connection == ConnectionState.CONNECTED && d.skinContact == false },
                d.batteryPercent?.takeIf { it < AppConstants.LOW_BATTERY_PCT && d.connection == ConnectionState.CONNECTED }?.let { "Strap battery low: $it %" },
            )
        }
    PulseCard(Modifier.clip(CardShape).clickable(onClick = onClick), padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(Pulse.danger.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(PulseIcon.HEART, Pulse.danger, size = 20.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (simulator) "Simulator" else "Polar H10", fontFamily = InterText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Pulse.text)
                Text(d.simulatorProfile?.name ?: d.deviceId ?: "No strap chosen", style = PulseType.smallNumber.copy(fontSize = 12.sp), color = Pulse.textLow, maxLines = 1)
            }
            StatusPill(label, dot)
        }
        problems.forEach {
            Spacer(Modifier.height(10.dp))
            Text(it, fontFamily = InterText, fontSize = 13.sp, color = Pulse.warning)
        }
    }
}

private fun minutes(ms: Long) = (ms / MS_PER_MINUTE).toInt()

@Composable
private fun PlanHeader(
    big: String,
    unit: String?,
    zoneLabel: String,
    zone: String,
    color: Color,
    open: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ValueUnit(big, unit, PulseType.bigNumber, Pulse.text, unitSize = 15.sp)
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Overline(zoneLabel)
            Text(zone, style = PulseType.number.copy(fontSize = 20.sp), color = color)
        }
        Spacer(Modifier.width(10.dp))
        Text(if (open) "▴" else "▾", fontSize = 18.sp, color = Pulse.textMid)
    }
}

@Composable
private fun FourByFourPlan(
    s: AppSettings,
    open: Boolean,
) {
    val cfg = s.toCoachingConfig().fourByFour
    PlanHeader(
        "${minutes(planTotalMs(cfg))}",
        "min",
        "${cfg.intervalCount} × ${minutes(cfg.workMs)} min at",
        "${cfg.workZone.lowBpm(s.hrMax)}–${cfg.workZone.highBpm(s.hrMax)}",
        Pulse.inZone.accent,
        open,
    )
    AnimatedVisibility(open) {
        Column {
            Spacer(Modifier.height(16.dp))
            PhaseTimeline(planSegments(cfg), 0f, Modifier.fillMaxWidth().height(16.dp), accent = Pulse.textMid)
            Spacer(Modifier.height(10.dp))
            Text(
                "Warmup ${minutes(cfg.warmupMs)} · ${cfg.intervalCount} × ${minutes(cfg.workMs)} min work, ${minutes(cfg.recoveryMs)} min easy · Cooldown ${minutes(cfg.cooldownMs)}",
                fontFamily = InterText,
                fontSize = 12.sp,
                color = Pulse.textMid,
            )
            Spacer(Modifier.height(14.dp))
            RowDivider()
            Spacer(Modifier.height(12.dp))
            HrMaxLine(s)
        }
    }
}

@Composable
private fun EasyPlan(
    s: AppSettings,
    open: Boolean,
    onEasyTargetMin: (Int) -> Unit,
) {
    val zone = s.easyZone.toZonePercent()
    val target = s.easyTargetMin
    PlanHeader(
        if (target == 0) "Open" else "$target",
        if (target == 0) null else "min",
        "Easy zone",
        "${zone.lowBpm(s.hrMax)}–${zone.highBpm(s.hrMax)}",
        Pulse.below.accent,
        open,
    )
    AnimatedVisibility(open) {
        Column {
            Spacer(Modifier.height(8.dp))
            SettingRow("Target duration", hint = "With a target you also get a halfway drift report") {
                val step = AppSettings.EASY_TARGET_STEP_MIN
                Stepper(
                    if (target == 0) "open" else "$target",
                    unit = if (target == 0) null else "min",
                    onMinus = { onEasyTargetMin((target - step).coerceAtLeast(0)) },
                    onPlus = { onEasyTargetMin((target + step).coerceAtMost(AppSettings.EASY_TARGET_RANGE.last)) },
                )
            }
            RowDivider()
            Spacer(Modifier.height(12.dp))
            HrMaxLine(s)
        }
    }
}

@Composable
private fun HrMaxLine(s: AppSettings) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Zones from HRmax ", fontFamily = InterText, fontSize = 13.sp, color = Pulse.textMid)
        Text("${s.hrMax}", style = PulseType.smallNumber.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = Pulse.text)
        Spacer(Modifier.width(8.dp))
        Tag(hrMaxSource(s))
    }
}

fun hrMaxSource(s: AppSettings): String =
    when {
        s.hrMaxOverride == null -> "estimated"
        s.hrMaxLearned -> "learned"
        else -> "manual"
    }
