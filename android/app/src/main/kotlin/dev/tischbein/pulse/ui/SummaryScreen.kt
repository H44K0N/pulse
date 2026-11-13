package dev.tischbein.pulse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.tischbein.pulse.coaching.SessionResult

/** Shown right after a session ends (while the coach says the summary). */
@Composable
fun SummaryScreen(
    result: SessionResult,
    onDone: () -> Unit,
) {
    val ui = summaryUi(result)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(ui.title, style = MaterialTheme.typography.headlineMedium)
        ui.newHrMax?.let {
            Card(Modifier.fillMaxWidth()) {
                Text(it, Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Stat("Duration", ui.duration)
                Stat("Average HR", ui.avgHr)
                Stat("Max HR", ui.maxHr)
                Stat("Time in target zone", ui.timeInTarget)
                ui.drift?.let { Stat("Cardiac drift", it) }
            }
        }
        if (ui.intervals.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TableRow(listOf("#", "Avg", "Peak", "In zone", "HRR60"), header = true)
                    HorizontalDivider()
                    ui.intervals.forEach { TableRow(listOf("${it.number}", it.avg, it.peak, it.pctInZone, it.hrr60)) }
                }
            }
            Text(
                "HRR60: how far heart rate dropped in the first minute of recovery (higher is better).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text("Saved on phone.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

@Composable
private fun Stat(
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    header: Boolean = false,
) {
    Row(Modifier.fillMaxWidth()) {
        cells.forEach {
            Text(
                it,
                Modifier.weight(1f),
                textAlign = TextAlign.End,
                fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}
