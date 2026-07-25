package com.drugme.app.ui.history

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drugme.app.domain.model.DoseStatus
import com.drugme.app.ui.theme.LocalDoseColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.io.File

private val stampFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onOpenSettings: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var noteItem by remember { mutableStateOf<com.drugme.app.data.local.dao.DoseWithMedication?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Schedule") },
                actions = {
                    IconButton(
                        onClick = { shareHistory(context, state.entries) },
                        enabled = state.entries.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Export history")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.padding(inner),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7, 30, 90).forEach { d ->
                        FilterChip(
                            selected = state.days == d,
                            onClick = { viewModel.setWindow(d) },
                            label = { Text("$d days") },
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    label = { Text("Search medication or note") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                HistoryFilters(state, viewModel)
            }
            if (state.calendar.isNotEmpty()) {
                item { CalendarStrip(state.calendar) }
            }
            item { AdherenceCard(state) }
            scheduleSection(
                title = "Overdue",
                entries = state.entries.filter {
                    it.dose.status == DoseStatus.PENDING && it.dose.effectiveAt.isBefore(state.now)
                },
                onEditNote = { noteItem = it },
            )
            scheduleSection(
                title = "Upcoming",
                entries = state.entries.filter {
                    it.dose.status == DoseStatus.PENDING && !it.dose.effectiveAt.isBefore(state.now)
                }.sortedBy { it.dose.effectiveAt },
                onEditNote = { noteItem = it },
            )
            scheduleSection(
                title = "Missed",
                entries = state.entries.filter { it.dose.status == DoseStatus.MISSED },
                onEditNote = { noteItem = it },
            )
            scheduleSection(
                title = "Taken",
                entries = state.entries.filter { it.dose.status == DoseStatus.TAKEN },
                onEditNote = { noteItem = it },
            )
            scheduleSection(
                title = "Skipped",
                entries = state.entries.filter { it.dose.status == DoseStatus.SKIPPED },
                onEditNote = { noteItem = it },
            )
            if (state.entries.isEmpty()) {
                item {
                    Text(
                        "No doses recorded in this period.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    noteItem?.let { item ->
        NoteDialog(
            medicationName = item.medication.name,
            initial = item.dose.note.orEmpty(),
            onDismiss = { noteItem = null },
            onSave = {
                viewModel.setNote(item.dose.id, it)
                noteItem = null
            },
        )
    }
}

@Composable
private fun HistoryFilters(state: HistoryState, viewModel: HistoryViewModel) {
    var medMenu by remember { mutableStateOf(false) }
    Column {
        Box {
            FilterChip(
                selected = state.medicationId != null,
                onClick = { medMenu = true },
                label = {
                    Text(
                        state.medications.firstOrNull { it.first == state.medicationId }?.second
                            ?: "All medications"
                    )
                },
            )
            DropdownMenu(expanded = medMenu, onDismissRequest = { medMenu = false }) {
                DropdownMenuItem(
                    text = { Text("All medications") },
                    onClick = { viewModel.setMedication(null); medMenu = false },
                )
                state.medications.forEach { (id, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = { viewModel.setMedication(id); medMenu = false },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(DoseStatus.PENDING, DoseStatus.TAKEN, DoseStatus.MISSED, DoseStatus.SKIPPED).forEach { status ->
                FilterChip(
                    selected = status in state.statuses,
                    onClick = { viewModel.toggleStatus(status) },
                    label = { Text(status.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }
        if (state.days != 7 || state.query.isNotBlank() ||
            state.medicationId != null || state.statuses.isNotEmpty()
        ) {
            TextButton(onClick = viewModel::clearFilters) { Text("Clear filters") }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.scheduleSection(
    title: String,
    entries: List<com.drugme.app.data.local.dao.DoseWithMedication>,
    onEditNote: (com.drugme.app.data.local.dao.DoseWithMedication) -> Unit,
) {
    if (entries.isEmpty()) return
    item(key = "header-$title") {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    items(entries, key = { "$title-${it.dose.id}" }) { item ->
        HistoryRow(item, onEditNote = { onEditNote(item) })
    }
}

@Composable
private fun CalendarStrip(days: List<CalendarDay>) {
    Column {
        Text("Recent pattern", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(days, key = { it.date.toString() }) { day ->
                val color = when {
                    day.missed > 0 -> LocalDoseColors.current.missed
                    day.taken > 0 && day.skipped == 0 -> LocalDoseColors.current.taken
                    day.skipped > 0 -> LocalDoseColors.current.skipped
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
                Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.16f))) {
                    Column(
                        Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(day.date.format(DateTimeFormatter.ofPattern("EEE")), style = MaterialTheme.typography.labelMedium)
                        Text(day.date.dayOfMonth.toString(), fontWeight = FontWeight.SemiBold)
                        Text("${day.taken}/${day.taken + day.missed + day.skipped}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun AdherenceCard(state: HistoryState) {
    val c = LocalDoseColors.current
    val a = state.adherence

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Last ${state.days} days",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                a.takenPercent?.let { "$it% taken" } ?: "No doses due yet",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stat("Taken", a.taken, c.taken)
                Stat("Missed", a.missed, c.missed)
                Stat("Skipped", a.skipped, c.skipped)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: Int, color: Color) {
    Column {
        Text(value.toString(), style = MaterialTheme.typography.titleLarge, color = color, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun HistoryRow(
    item: com.drugme.app.data.local.dao.DoseWithMedication,
    onEditNote: () -> Unit,
) {
    val c = LocalDoseColors.current
    val zone = ZoneId.systemDefault()

    val (icon: ImageVector, tint: Color, label: String) = when (item.dose.status) {
        DoseStatus.TAKEN -> Triple(Icons.Default.CheckCircle, c.taken, "Taken")
        DoseStatus.MISSED -> Triple(Icons.Default.ErrorOutline, c.missed, "Missed")
        DoseStatus.SKIPPED -> Triple(Icons.Default.RemoveCircleOutline, c.skipped, "Skipped")
        DoseStatus.PENDING -> Triple(Icons.Default.Schedule, c.pending, "Upcoming")
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.medication.name.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "${item.dose.scheduledAt.atZone(zone).format(stampFmt)} · ${item.unit.format(item.amount)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            item.dose.note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onEditNote) {
            Icon(Icons.Default.EditNote, contentDescription = "Add or edit note")
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

@Composable
private fun NoteDialog(
    medicationName: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note for $medicationName") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Optional context") },
                placeholder = { Text("e.g. felt unwell") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (initial.isNotBlank()) {
                    TextButton(onClick = { onSave("") }) { Text("Remove") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun shareHistory(
    context: Context,
    entries: List<com.drugme.app.data.local.dao.DoseWithMedication>,
) {
    val csv = buildString {
        appendLine("date,time,medication,amount,unit,status,note")
        entries.forEach { item ->
            val local = item.dose.scheduledAt.atZone(ZoneId.systemDefault())
            appendLine(
                listOf(
                    local.toLocalDate().toString(),
                    local.toLocalTime().toString(),
                    item.medication.name,
                    item.amount.toString(),
                    item.unit.label,
                    item.dose.status.name,
                    item.dose.note.orEmpty(),
                ).joinToString(",") { csvCell(it) }
            )
        }
    }
    val directory = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(directory, "drugme-history.csv").apply { writeText(csv) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Share medication history",
        )
    )
}

private fun csvCell(value: String): String = "\"${value.replace("\"", "\"\"")}\""
