package com.drugme.app.ui.home

import android.content.Intent
import android.provider.Settings

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
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Inventory2
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drugme.app.data.local.dao.DoseWithMedication
import com.drugme.app.domain.model.DoseStatus
import com.drugme.app.ui.theme.LocalDoseColors
import java.time.LocalDate
import java.time.Instant
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
    val context = LocalContext.current
    var adjustStock by remember { mutableStateOf<StockAlert?>(null) }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshPermissionState()
        onPauseOrDispose { }
    }

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
        LazyColumn(
            modifier = Modifier.padding(inner).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Greeting(state.displayName) }
            if (state.date == LocalDate.now() && !state.loading) {
                if (state.appUpdate.available || state.appUpdate.checking || state.appUpdate.downloading) {
                    item {
                        AppUpdateCard(
                            state = state.appUpdate,
                            onInstall = {
                                if (!viewModel.installUpdate()) {
                                    context.startActivity(viewModel.unknownSourcesIntent())
                                }
                            },
                        )
                    }
                }
                item { TodaySummary(state) }
                item {
                    ReminderHealthCard(
                        health = state.health,
                        testSent = state.testReminderSent,
                        onTest = viewModel::sendTestReminder,
                        onFixExact = onFixExactAlarms,
                        onFixNotifications = {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            )
                        },
                        onFixBattery = {
                            runCatching {
                                context.startActivity(
                                    com.drugme.app.ui.onboarding.OemBatteryGuidance
                                        .requestIgnoreBatteryOptimizationsIntent(context)
                                )
                            }
                        },
                    )
                }
                if (state.stockAlerts.isNotEmpty()) {
                    item { LowStockCard(state.stockAlerts, onAdjust = { adjustStock = it }) }
                }
            }
            item {
                DateBar(
                    date = state.date,
                    onPrev = { viewModel.showDate(state.date.minusDays(1)) },
                    onNext = { viewModel.showDate(state.date.plusDays(1)) },
                    onToday = viewModel::today,
                )
            }
            when {
                state.loading -> Unit
                !state.hasMedications -> item { EmptyState(onAddMedication) }
                state.doses.isEmpty() -> item { NoDosesToday() }
                else -> items(state.doses, key = { it.dose.id }) { item ->
                    Box(Modifier.padding(horizontal = 16.dp)) {
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

    adjustStock?.let { alert ->
        StockAdjustmentDialog(
            alert = alert,
            onDismiss = { adjustStock = null },
            onSave = { amount ->
                viewModel.setStock(alert.medication.id, amount)
                adjustStock = null
            },
        )
    }
}

/**
 * "Hello, Jason — have you taken your drugs?"
 *
 * Falls back to a plain "Hello" when signed out: sign-in is optional, and a greeting that
 * only works with an account would quietly punish the people who declined one.
 */
@Composable
private fun TodaySummary(state: HomeState) {
    val elapsed = state.doses.count { it.dose.status != DoseStatus.PENDING }
    val total = state.doses.size
    val next = state.doses.firstOrNull { it.dose.status == DoseStatus.PENDING }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                when {
                    total == 0 -> "Your day is clear"
                    next == null -> "All scheduled doses handled"
                    else -> "Next: ${next.medication.name.replaceFirstChar { it.uppercase() }}"
                },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (next != null) {
                Text(
                    "${next.dose.effectiveAt.atZone(ZoneId.systemDefault()).toLocalTime().format(timeFmt)} · " +
                        next.unit.format(next.amount),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (total > 0) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { elapsed.toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "$elapsed of $total handled today",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun AppUpdateCard(
    state: com.drugme.app.data.update.AppUpdateState,
    onInstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                state.title ?: "Checking for a DrugMe update",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                when {
                    state.checking -> "Checking GitHub Releases…"
                    state.downloading -> "Downloading and verifying the update…"
                    state.downloaded -> "Downloaded and signature-verified. Ready to install."
                    else -> "A new version is available."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            if (state.downloaded) {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onInstall) { Text("Review and install") }
            }
        }
    }
}

@Composable
private fun ReminderHealthCard(
    health: ReminderHealth,
    testSent: Boolean?,
    onTest: () -> Unit,
    onFixExact: () -> Unit,
    onFixNotifications: () -> Unit,
    onFixBattery: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.HealthAndSafety,
                    contentDescription = null,
                    tint = if (health.healthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Reminder health", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (health.healthy) "Ready to remind you" else "One or more settings need attention",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            HealthRow("Notifications", health.notificationsOk, onFixNotifications)
            HealthRow("Exact timing", health.exactAlarmsOk, onFixExact)
            HealthRow("Background access", !health.batteryOptimized, onFixBattery)
            health.nextReminder?.let {
                Text(
                    "Next alarm: ${formatReminderInstant(it)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onTest, enabled = health.notificationsOk) {
                Text("Send test reminder")
            }
            testSent?.let {
                Text(
                    if (it) "Test reminder sent." else "Notifications are blocked. Fix them above.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (it) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun HealthRow(label: String, ok: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f))
        if (!ok) TextButton(onClick = onFix) { Text("Fix") }
    }
}

@Composable
private fun LowStockCard(alerts: List<StockAlert>, onAdjust: (StockAlert) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Inventory2, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text("Refills needed", style = MaterialTheme.typography.titleMedium)
            }
            alerts.take(3).forEach { alert ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(alert.medication.name.replaceFirstChar { it.uppercase() })
                        Text(
                            alert.forecast.daysRemaining?.let {
                                if (it <= 0) "Running out now" else "About $it days left"
                            } ?: "Running low",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onAdjust(alert) }) { Text("Update stock") }
                }
            }
        }
    }
}

@Composable
private fun StockAdjustmentDialog(
    alert: StockAlert,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit,
) {
    var value by remember(alert.medication.id) {
        mutableStateOf(alert.medication.stockAmount?.toString().orEmpty())
    }
    val parsed = value.replace(',', '.').toDoubleOrNull()
    val unit = alert.medication.stockUnit ?: alert.medication.doseUnit
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update ${alert.medication.name}") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                label = { Text("Amount left (${unit.label})") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onSave) }, enabled = parsed != null && parsed >= 0) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatReminderInstant(instant: Instant): String {
    val local = instant.atZone(ZoneId.systemDefault())
    return "${local.toLocalDate().format(DateTimeFormatter.ofPattern("EEE d MMM"))} at " +
        local.toLocalTime().format(timeFmt)
}

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
                        "${time.format(timeFmt)} · ${item.unit.format(item.amount)}" +
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
