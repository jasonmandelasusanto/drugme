package com.drugme.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.drugme.app.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drugme.app.data.local.dao.DoseWithMedication
import com.drugme.app.domain.model.DoseStatus
import com.drugme.app.ui.theme.LocalDoseColors
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val dayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMM")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAddMedication: () -> Unit,
    onEditMedication: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenProfile: () -> Unit,
    onFixExactAlarms: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.logo),
                            contentDescription = null,
                            modifier = Modifier.size(28.dp).clip(CircleShape),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("DrugMe")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Default.History, contentDescription = "History")
                    }
                    IconButton(onClick = onOpenProfile) {
                        Icon(Icons.Default.AccountCircle, contentDescription = "Profile")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddMedication) {
                Icon(Icons.Default.Add, contentDescription = "Add medication")
            }
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            Greeting(state.displayName)
            DateBar(
                date = state.date,
                onPrev = { viewModel.showDate(state.date.minusDays(1)) },
                onNext = { viewModel.showDate(state.date.plusDays(1)) },
                onToday = viewModel::today,
            )

            if (state.exactAlarmsBlocked) {
                ExactAlarmWarning(onFix = onFixExactAlarms)
            }

            when {
                state.loading -> Unit
                !state.hasMedications -> EmptyState(onAddMedication)
                state.doses.isEmpty() -> NoDosesToday()
                else -> LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.doses, key = { it.dose.id }) { item ->
                        DoseCard(
                            item = item,
                            onTaken = { viewModel.markTaken(item.dose.id) },
                            onSkip = { viewModel.markSkipped(item.dose.id) },
                            onSnooze = { viewModel.snooze(item.dose.id) },
                            onSetStatus = { viewModel.setStatus(item.dose.id, it) },
                            onClick = { onEditMedication(item.medication.id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * "Hello, Jason — have you taken your drugs?"
 *
 * Falls back to a plain "Hello" when signed out: sign-in is optional, and a greeting that
 * only works with an account would quietly punish the people who declined one.
 */
@Composable
private fun Greeting(displayName: String?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            displayName?.let { stringResource(R.string.greeting, it) }
                ?: stringResource(R.string.greeting_anon),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.greeting_question),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DateBar(date: LocalDate, onPrev: () -> Unit, onNext: () -> Unit, onToday: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onPrev) { Icon(Icons.Default.ChevronLeft, contentDescription = "Previous day") }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(date.format(dayFmt), style = MaterialTheme.typography.titleMedium)
            if (date != LocalDate.now()) {
                TextButton(onClick = onToday) { Text("Back to today") }
            }
        }
        IconButton(onClick = onNext) { Icon(Icons.Default.ChevronRight, contentDescription = "Next day") }
    }
}

/**
 * Shown when exact alarms are unavailable.
 *
 * Worth the interruption: without the permission every reminder silently degrades to
 * inexact, and the user's mental model ("the app will tell me at 8") quietly stops being
 * true. Better to say so than to look like it is working.
 */
@Composable
private fun ExactAlarmWarning(onFix: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Reminders may be late",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    "Exact alarms are turned off, so doses can be delayed by several minutes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            TextButton(onClick = onFix) { Text("Fix") }
        }
    }
}

@Composable
private fun DoseCard(
    item: DoseWithMedication,
    onTaken: () -> Unit,
    onSkip: () -> Unit,
    onSnooze: () -> Unit,
    onSetStatus: (DoseStatus) -> Unit,
    onClick: () -> Unit,
) {
    val doseColors = LocalDoseColors.current
    val status = item.dose.status
    val zone = ZoneId.systemDefault()
    val time = item.dose.effectiveAt.atZone(zone).toLocalTime()

    // Icon and label carry the status; color only reinforces it. Red/green is the most
    // common color-vision deficiency, and "did I take this?" is the question the whole app
    // exists to answer — it must never depend on distinguishing two hues.
    val (icon: ImageVector, tint: Color, label: String) = when (status) {
        DoseStatus.TAKEN -> Triple(Icons.Default.CheckCircle, doseColors.taken, "Taken")
        DoseStatus.MISSED -> Triple(Icons.Default.ErrorOutline, doseColors.missed, "Missed")
        DoseStatus.SKIPPED -> Triple(Icons.Default.RemoveCircleOutline, doseColors.skipped, "Skipped")
        DoseStatus.PENDING ->
            if (item.dose.snoozedUntil != null) {
                Triple(Icons.Default.Snooze, doseColors.pending, "Snoozed")
            } else {
                Triple(Icons.Default.Schedule, doseColors.pending, "Due")
            }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.medication.name.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.titleMedium,
                        textDecoration = if (status == DoseStatus.SKIPPED) TextDecoration.LineThrough else null,
                    )
                    Text(
                        "${time.format(timeFmt)} · ${item.medication.doseUnit.format(item.medication.doseAmount)}" +
                            item.medication.foodRelation.notificationSuffix(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    item.medication.diseaseName?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // The status is a menu, not a label. A dose marked by mistake — or marked
                // taken and then not actually taken — has to be correctable, or the record
                // silently diverges from reality while still looking authoritative.
                StatusMenu(current = status, onPick = onSetStatus, tint = tint, label = label)
            }

            if (status == DoseStatus.PENDING) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onTaken) { Text("Taken") }
                    OutlinedButton(onClick = onSnooze) { Text("Snooze") }
                    OutlinedButton(onClick = onSkip) { Text("Skip") }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.logo),
                contentDescription = null,
                modifier = Modifier.size(80.dp).clip(CircleShape),
            )
            Spacer(Modifier.height(16.dp))
            Text("Build your reminder routine", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Add your medications, set the times that fit your day, and DrugMe reminds you " +
                    "when to take each one. It's your routine — you decide how it works.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onAdd) { Text("Add your first medication") }
        }
    }
}

@Composable
private fun NoDosesToday() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "Nothing scheduled for this day.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Tap the status to change it.
 *
 * MISSED is absent on purpose: only the system assigns it, after the grace window. Letting
 * a user pick it would blur the distinction that keeps adherence honest — skipped is a
 * decision, missed is not.
 */
@Composable
private fun StatusMenu(
    current: DoseStatus,
    onPick: (DoseStatus) -> Unit,
    tint: androidx.compose.ui.graphics.Color,
    label: String,
) {
    var open by remember { mutableStateOf(false) }

    Box {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = tint,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clickable { open = true }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                DoseStatus.TAKEN to "Taken",
                DoseStatus.SKIPPED to "Skipped",
                DoseStatus.PENDING to "Not yet taken",
            ).forEach { (status, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    enabled = status != current,
                    onClick = { onPick(status); open = false },
                )
            }
        }
    }
}
