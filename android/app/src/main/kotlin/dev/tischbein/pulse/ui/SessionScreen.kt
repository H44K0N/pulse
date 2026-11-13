package dev.tischbein.pulse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tischbein.pulse.session.ActiveSession

/**
 * Bare debug view of a running session: the engine's raw state, Pause/Resume and End.
 * Milestone 3 replaces this with the real live screen.
 */
@Composable
fun SessionScreen(session: ActiveSession) {
    val state by session.state.collectAsStateWithLifecycle()
    var confirmEnd by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Session running", style = MaterialTheme.typography.headlineSmall)
        Text(session.sourceLabel)
        Text(
            state.toString().substringAfter('(').removeSuffix(")").split(", ").joinToString("\n"),
            fontFamily = FontFamily.Monospace,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.paused) {
                Button(onClick = session::resume) { Text("Resume") }
            } else {
                Button(onClick = session::pause, enabled = state.started) { Text("Pause") }
            }
            OutlinedButton(onClick = { confirmEnd = true }) { Text("End") }
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End session?") },
            text = { Text("The session is saved as it is now.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnd = false
                    session.end()
                }) { Text("End") }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep going") } },
        )
    }
}
