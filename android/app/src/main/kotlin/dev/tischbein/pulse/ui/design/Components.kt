package dev.tischbein.pulse.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import dev.tischbein.pulse.AppConstants
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tischbein.pulse.ui.theme.InterText
import dev.tischbein.pulse.ui.theme.Pulse
import dev.tischbein.pulse.ui.theme.PulseType

/*
 * Small building blocks shared by all screens. Flat, rounded, minimal chrome: surfaces are a
 * step lighter than the background, with no shadows and at most a hairline stroke.
 */

val CardShape = RoundedCornerShape(24.dp)
val ScreenPadding = 20.dp

/** A rounded surface card. */
@Composable
fun PulseCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(20.dp),
    color: Color = Pulse.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().clip(CardShape).background(color).padding(padding),
        content = content,
    )
}

/** All-caps small label ("AVG HR"). */
@Composable
fun Overline(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Pulse.textLow,
) {
    Text(text.uppercase(), style = PulseType.overline, color = color, modifier = modifier, maxLines = 1)
}

/** Section title above a group of cards, with an optional trailing hint. */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, fontFamily = InterText, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Pulse.text, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, fontFamily = InterText, fontSize = 13.sp, color = Pulse.textLow) }
    }
}

/** A labelled number: "AVG HR / 152 bpm". */
@Composable
fun Stat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    valueColor: Color = Pulse.text,
    big: Boolean = false,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Overline(label)
        ValueUnit(value, unit, if (big) PulseType.bigNumber else PulseType.number, valueColor)
    }
}

/**
 * A number with a small unit after it ("152 bpm"), as ONE text so both share a baseline
 * whatever their sizes.
 */
@Composable
fun ValueUnit(
    value: String,
    unit: String?,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    unitSize: TextUnit = 13.sp,
    unitColor: Color = Pulse.textMid,
) {
    Text(
        buildAnnotatedString {
            append(value)
            if (unit != null) {
                withStyle(SpanStyle(fontFamily = InterText, fontWeight = FontWeight.Normal, fontSize = unitSize, color = unitColor, letterSpacing = 0.sp)) {
                    append("\u2009$unit")
                }
            }
        },
        style = style,
        color = color,
        maxLines = 1,
        modifier = modifier,
    )
}

/** Direction of a change and whether that is good. */
enum class Trend { UP_GOOD, UP_BAD, DOWN_GOOD, DOWN_BAD, FLAT }

/** "▲ 12 %" in green / "▼ 3 bpm" in orange; arrow and sign carry the meaning, color only helps. */
@Composable
fun DeltaChip(
    text: String,
    trend: Trend,
    modifier: Modifier = Modifier,
) {
    val (arrow, color) =
        when (trend) {
            Trend.UP_GOOD -> "▲" to Pulse.inZone.accent
            Trend.DOWN_GOOD -> "▼" to Pulse.inZone.accent
            Trend.UP_BAD -> "▲" to Pulse.above.accent
            Trend.DOWN_BAD -> "▼" to Pulse.above.accent
            Trend.FLAT -> "■" to Pulse.textMid
        }
    Row(
        modifier.clip(CircleShape).background(color.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(arrow, fontSize = 8.sp, color = color)
        Spacer(Modifier.width(4.dp))
        Text(text, style = PulseType.smallNumber.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = color, maxLines = 1)
    }
}

/** A small rounded tag ("Learned", "4x4"). */
@Composable
fun Tag(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Pulse.textMid,
    filled: Boolean = false,
) {
    Text(
        text,
        fontFamily = InterText,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        color = if (filled) Pulse.bg else color,
        maxLines = 1,
        modifier =
            modifier
                .clip(CircleShape)
                .background(if (filled) color else color.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Status dot + text, e.g. "● H10 connected". */
@Composable
fun StatusPill(
    text: String,
    dot: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.clip(CircleShape).background(Pulse.surfaceRaised).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(7.dp))
        Text(text, fontFamily = InterText, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = Pulse.text, maxLines = 1)
    }
}

/** Big pill button. [primary] = accent fill, else a quiet surface button. */
@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    color: Color = Pulse.accent,
    height: Dp = 64.dp,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
) {
    Column(
        modifier
            .height(height)
            .clip(CircleShape)
            .background(if (primary) color.copy(alpha = if (enabled) 1f else 0.3f) else Pulse.surfaceRaised)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, fontFamily = InterText, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = if (primary) Pulse.bg else color)
        subtitle?.let { Text(it, fontFamily = InterText, fontSize = 12.sp, color = if (primary) Pulse.bg.copy(alpha = 0.7f) else Pulse.textMid) }
    }
}

/** "−  10 min  +" stepper for small whole numbers (nicer than a text field for minutes/%). */
@Composable
fun Stepper(
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    onMinus: () -> Unit = {},
    onPlus: () -> Unit = {},
) {
    Row(
        modifier.clip(CircleShape).background(Pulse.surfaceRaised).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperButton("−", onMinus)
        Box(Modifier.width(72.dp), contentAlignment = Alignment.Center) {
            ValueUnit(value, unit, PulseType.smallNumber.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), Pulse.text, unitSize = 12.sp)
        }
        StepperButton("+", onPlus)
    }
}

@Composable
private fun StepperButton(
    symbol: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(Pulse.bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, fontFamily = InterText, fontWeight = FontWeight.Medium, fontSize = 18.sp, color = Pulse.text)
    }
}

/** One row of a grouped settings card: label (+ hint) on the left, a control on the right. */
@Composable
fun SettingRow(
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    control: @Composable RowScope.() -> Unit,
) {
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontFamily = InterText, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = Pulse.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            hint?.let { Text(it, fontFamily = InterText, fontSize = 12.sp, color = Pulse.textLow, maxLines = 2) }
        }
        Spacer(Modifier.width(12.dp))
        control()
    }
}

/** Hairline between rows of a grouped card. */
@Composable
fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Pulse.stroke))
}

/** Segmented control (e.g. 4x4 | Easy). */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    background: Color = Pulse.surface,
    onSelect: (Int) -> Unit = {},
) {
    Row(modifier.clip(CircleShape).background(background).padding(4.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(CircleShape)
                    .background(if (on) Pulse.surfaceRaised else Color.Transparent)
                    .then(if (on) Modifier.border(1.dp, Pulse.stroke, CircleShape) else Modifier)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontFamily = InterText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (on) Pulse.text else Pulse.textMid)
            }
        }
    }
}

/** The app's main destinations. The live screen and summaries hide the bar. */
enum class Destination(val label: String, val icon: PulseIcon) {
    TRAIN("Train", PulseIcon.TRAIN),
    PROGRESS("Progress", PulseIcon.PROGRESS),
    SETTINGS("Settings", PulseIcon.SETTINGS),
}

/** Slim bottom navigation: icon + label, the selected one in the accent color with a soft pill. */
@Composable
fun BottomBar(
    selected: Destination,
    modifier: Modifier = Modifier,
    onSelect: (Destination) -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().background(Pulse.bg).padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        Destination.entries.forEach { d ->
            val on = d == selected
            Column(
                Modifier.clip(RoundedCornerShape(16.dp)).clickable { onSelect(d) }.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.width(56.dp).height(30.dp).clip(CircleShape).background(if (on) Pulse.accent.copy(alpha = 0.14f) else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(d.icon, tint = if (on) Pulse.accent else Pulse.textMid, size = 20.dp)
                }
                Spacer(Modifier.height(4.dp))
                Text(d.label, fontFamily = InterText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = if (on) Pulse.text else Pulse.textMid)
            }
        }
    }
}

/**
 * End button that needs a hold of [AppConstants.HOLD_TO_END_MS], so a sweaty hand brushing the
 * screen cannot stop the workout (no confirm dialog: that is one more thing to read mid-run).
 * While held, a red fill sweeps across, the button grows a little and warms up; letting go
 * early rewinds it. A quick tap only says "Keep holding". TalkBack: a long-press action.
 *
 * @param previewProgress draws the button part-way through a hold (screenshots only).
 */
@Composable
fun HoldToEndButton(
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 64.dp,
    previewProgress: Float = 0f,
) {
    val progress = remember { Animatable(previewProgress) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val currentOnEnd by rememberUpdatedState(onEnd)
    var hint by remember { mutableStateOf(false) }
    LaunchedEffect(hint) {
        if (hint) {
            delay(HINT_VISIBLE_MS)
            hint = false
        }
    }
    val p = progress.value
    Box(
        modifier
            .height(height)
            .graphicsLayer {
                scaleX = 1f + 0.05f * p
                scaleY = 1f + 0.05f * p
            }.clip(CircleShape)
            .background(lerp(Pulse.surfaceRaised, Pulse.danger.copy(alpha = 0.22f).compositeOver(Pulse.bg), p))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    val hold =
                        scope.launch {
                            val remaining = ((1f - progress.value) * AppConstants.HOLD_TO_END_MS).toInt()
                            progress.animateTo(1f, tween(remaining, easing = LinearEasing))
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentOnEnd()
                        }
                    waitForUpOrCancellation()
                    if (progress.value < 1f) {
                        hold.cancel()
                        if (progress.value < HINT_BELOW) hint = true
                        scope.launch { progress.animateTo(0f, tween(REWIND_MS)) }
                    }
                }
            }.semantics(mergeDescendants = true) {
                role = Role.Button
                onLongClick(label = "End session") {
                    currentOnEnd()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(p).background(Pulse.danger.copy(alpha = 0.45f)))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("End", fontFamily = InterText, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = if (p > 0.5f) Pulse.text else Pulse.danger)
            Text(if (hint) "Keep holding" else "hold", fontFamily = InterText, fontSize = 12.sp, color = Pulse.textMid)
        }
    }
}

private const val HINT_VISIBLE_MS = 2_500L
private const val HINT_BELOW = 0.25f
private const val REWIND_MS = 250
