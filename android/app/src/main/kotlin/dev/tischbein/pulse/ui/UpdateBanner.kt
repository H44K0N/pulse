package dev.tischbein.pulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tischbein.pulse.ui.design.PillButton
import dev.tischbein.pulse.ui.design.PulseCard
import dev.tischbein.pulse.ui.theme.InterText
import dev.tischbein.pulse.ui.theme.Pulse
import dev.tischbein.pulse.update.UpdatePhase
import dev.tischbein.pulse.update.UpdateTexts
import dev.tischbein.pulse.update.UpdateView

/** The one button the update shows (or none while it works). */
enum class UpdateButton(val label: String?) {
    UPDATE("Update"),
    TRY_AGAIN("Try again"),
    OPEN_SETTINGS("Open settings"),
    NONE(null),
}

/** The update banner (Train) and the same card in Settings, as plain values (UpdateCardTest). */
data class UpdateCardUi(
    /** "Update to 0.5.2". */
    val title: String,
    /** The release note (empty: none). */
    val notes: String,
    /** What is happening, or what went wrong ([problem]); null when there is nothing to say. */
    val status: String?,
    val problem: Boolean = false,
    /** 0..100 while downloading. */
    val progress: Int? = null,
    val button: UpdateButton,
)

object UpdateCard {
    /**
     * Null when there is nothing to offer, and always while a session is running or being set
     * up ([sessionRunning]): no banner then, whatever the update is doing.
     */
    fun of(
        view: UpdateView?,
        sessionRunning: Boolean,
    ): UpdateCardUi? {
        if (sessionRunning) return null
        val offer = view?.offer ?: return null
        val title = "Update to ${offer.versionName}"
        val notes = offer.notes
        return when (val p = view.phase) {
            UpdatePhase.Ready -> UpdateCardUi(title, notes, null, button = UpdateButton.UPDATE)
            UpdatePhase.AskPermission -> UpdateCardUi(title, notes, UpdateTexts.PERMISSION, button = UpdateButton.OPEN_SETTINGS)
            is UpdatePhase.Downloading -> UpdateCardUi(title, notes, "${UpdateTexts.DOWNLOADING} ${p.percent} %", progress = p.percent, button = UpdateButton.NONE)
            UpdatePhase.Checking -> UpdateCardUi(title, notes, UpdateTexts.CHECKING, progress = 100, button = UpdateButton.NONE)
            UpdatePhase.Downloaded -> UpdateCardUi(title, notes, UpdateTexts.DOWNLOADED, button = UpdateButton.UPDATE)
            UpdatePhase.Installing -> UpdateCardUi(title, notes, UpdateTexts.INSTALLING, button = UpdateButton.NONE)
            is UpdatePhase.Failed -> UpdateCardUi(title, notes, p.reason, problem = true, button = UpdateButton.TRY_AGAIN)
        }
    }

    /** Under Train's Start: why Start waits, or null. */
    fun startBlocked(view: UpdateView?): String? = if (view?.phase == UpdatePhase.Installing) UpdateTexts.START_WAITS else null
}

/** What the update's button does: Update and Try again tap Update; Open settings opens Android's page. */
data class UpdateActions(
    val onUpdate: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
) {
    fun of(button: UpdateButton): () -> Unit =
        when (button) {
            UpdateButton.UPDATE, UpdateButton.TRY_AGAIN -> onUpdate
            UpdateButton.OPEN_SETTINGS -> onOpenSettings
            UpdateButton.NONE -> ({})
        }
}

/** "Update to 0.5.2", the note, what is happening, one button (or the progress). */
@Composable
fun UpdateBanner(
    ui: UpdateCardUi,
    actions: UpdateActions,
    modifier: Modifier = Modifier,
) {
    PulseCard(modifier) {
        Text(ui.title, fontFamily = InterText, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, color = Pulse.text)
        if (ui.notes.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(ui.notes, fontFamily = InterText, fontSize = 14.sp, lineHeight = 19.sp, color = Pulse.textMid)
        }
        ui.status?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, fontFamily = InterText, fontSize = 14.sp, lineHeight = 19.sp, color = if (ui.problem) Pulse.warning else Pulse.text)
        }
        ui.progress?.let { pct ->
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Pulse.surfaceRaised), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.fillMaxWidth(pct / 100f).fillMaxHeight().clip(CircleShape).background(Pulse.accent))
            }
        }
        ui.button.label?.let { label ->
            Spacer(Modifier.height(14.dp))
            PillButton(label, Modifier.fillMaxWidth(), height = 52.dp, onClick = actions.of(ui.button))
        }
    }
}
