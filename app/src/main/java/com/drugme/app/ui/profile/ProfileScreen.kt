package com.drugme.app.ui.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.drugme.app.R
import com.drugme.app.ui.components.SectionCard
import com.drugme.app.ui.theme.LocalDoseColors
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onNavigateBack: () -> Unit,
    onEditMedication: (String) -> Unit,
    onSignedOut: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.deleted) {
        if (state.deleted) onSignedOut()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.padding(inner),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { AccountCard(state) }
            item { AdherenceCard(state) }
            item { PunctualityCard(state) }
            item { UsageCard(state) }
            item { MedicationsCard(state, onEditMedication) }
            item { DangerZone(state, onSignOut = { viewModel.signOut(); onSignedOut() }, onDelete = { confirmDelete = true }) }
            state.error?.let {
                item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    if (confirmDelete) {
        DeleteAccountDialog(
            busy = state.deleting,
            onConfirm = { viewModel.deleteAccount() },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun AccountCard(state: ProfileState) {
    SectionCard(title = "Account") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val photo = state.user?.photoUrl
            if (photo != null) {
                AsyncImage(
                    model = photo,
                    contentDescription = "Profile picture",
                    modifier = Modifier.size(64.dp).clip(CircleShape),
                    // Falls back to the app logo while loading and if the fetch fails —
                    // a broken image where a face should be looks like a bug.
                    placeholder = painterResource(R.drawable.logo),
                    error = painterResource(R.drawable.logo),
                )
            } else {
                Icon(
                    Icons.Default.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    state.user?.displayName ?: "Not signed in",
                    style = MaterialTheme.typography.titleLarge,
                )
                state.user?.email?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.user == null) {
                    Text(
                        "Your medications are saved on this phone only.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AdherenceCard(state: ProfileState) {
    val c = LocalDoseColors.current
    val a = state.adherence

    SectionCard(title = "Adherence · last 30 days") {
        Text(
            a.takenPercent?.let { "$it% taken" } ?: "No doses due yet",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        a.takenPercent?.let {
            LinearProgressIndicator(
                progress = { it / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Stat("Taken", a.taken.toString(), c.taken)
            Stat("Missed", a.missed.toString(), c.missed)
            Stat("Skipped", a.skipped.toString(), c.skipped)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            // Stated plainly because the two are not the same thing and the difference
            // matters: one is a decision, the other isn't.
            "Skipped means you decided not to take it. Missed means the time passed without an answer.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PunctualityCard(state: ProfileState) {
    val c = LocalDoseColors.current
    val p = state.punctuality

    SectionCard(title = "Punctuality · last 30 days") {
        Text(
            p.onTimePercent?.let { "$it% on time" } ?: "Nothing taken yet",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Within 30 minutes of the scheduled time",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (p.total > 0) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Stat("On time", p.onTimeCount.toString(), c.taken)
                Stat("Late", p.lateCount.toString(), c.missed)
                Stat("Early", p.earlyCount.toString(), c.skipped)
            }
            p.medianDelayMinutes?.let { median ->
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        // Median, because one dose taken eight hours late would drag a mean
                        // into nonsense while most days were fine.
                        when {
                            abs(median) <= 5 -> "Typically right on time"
                            median > 0 -> "Typically ${formatMinutes(median)} late"
                            else -> "Typically ${formatMinutes(-median)} early"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageCard(state: ProfileState) {
    SectionCard(title = "How much you've taken") {
        if (state.usage.isEmpty()) {
            Text(
                "Nothing recorded yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        Text(
            "Tracking for ${state.trackingSinceDays} day${if (state.trackingSinceDays == 1) "" else "s"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        state.usage.forEach { u ->
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(
                    u.name.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Total: ${fmt(u.totalAmount)} ${u.unit.lowercase()} · ${u.totalDoses} doses",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Past week: ${fmt(u.weekAmount)} ${u.unit.lowercase()} · ${u.weekDoses} doses",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Average: ${fmt(u.averagePerDay)} ${u.unit.lowercase()} per day",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun MedicationsCard(state: ProfileState, onEdit: (String) -> Unit) {
    val c = LocalDoseColors.current

    SectionCard(title = "Your medications") {
        if (state.medications.isEmpty()) {
            Text(
                "None yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        state.medications.forEach { item ->
            val med = item.medication
            val forecast = state.forecasts[med.id]

            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onEdit(med.id) }
                    .padding(vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            med.name.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            med.doseUnit.format(med.doseAmount) + med.foodRelation.notificationSuffix(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        med.diseaseName?.let {
                            Text(
                                "For $it",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (!med.isActive) {
                        Text("Paused", style = MaterialTheme.typography.labelLarge, color = c.skipped)
                    }
                }

                // Stock line, only when the user opted into tracking.
                if (med.stockAmount != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (forecast?.needsRefillWarning == true) Icons.Default.Warning else Icons.Default.Inventory2,
                            contentDescription = null,
                            tint = if (forecast?.needsRefillWarning == true) c.missed else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            buildString {
                                append("${fmt(med.stockAmount)} left")
                                forecast?.daysRemaining?.let { d ->
                                    append(
                                        when {
                                            d <= 0 -> " · out now"
                                            d == 1 -> " · runs out tomorrow"
                                            else -> " · about $d days"
                                        }
                                    )
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (forecast?.needsRefillWarning == true) c.missed
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun DangerZone(state: ProfileState, onSignOut: () -> Unit, onDelete: () -> Unit) {
    SectionCard(title = "Account actions") {
        if (state.user != null) {
            OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Logout, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Sign out")
            }
            Text(
                // Says what it does, because "sign out" is ambiguous in an app that also
                // stores things locally, and people reasonably fear losing their data.
                "Your medications stay on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
        }

        OutlinedButton(
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text("Delete account and all data")
        }
    }
}

/**
 * Deletion confirmation.
 *
 * Requires typing DELETE rather than tapping a red button. This wipes an entire medical
 * history irreversibly, and the encryption means nobody — including us — can restore it.
 * A single tap next to "Sign out" is too easy to hit by accident for something with no
 * undo.
 */
@Composable
private fun DeleteAccountDialog(busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val confirmed = typed.trim().equals("DELETE", ignoreCase = false)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Delete everything?") },
        text = {
            Column {
                Text("This permanently deletes:")
                Spacer(Modifier.height(8.dp))
                Text("• Your medications and schedules")
                Text("• Your full dose history")
                Text("• Your encrypted backup")
                Text("• Your account")
                Spacer(Modifier.height(12.dp))
                Text(
                    "This cannot be undone. Because your data is encrypted with your passphrase, " +
                        "we cannot recover it for you afterwards.",
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Type DELETE to confirm") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmed && !busy) {
                Text(if (busy) "Deleting…" else "Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        },
    )
}

@Composable
private fun Stat(label: String, value: String, color: Color) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge, color = color, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun fmt(v: Double): String =
    if (v % 1.0 == 0.0) v.toLong().toString() else String.format("%.1f", v)

private fun formatMinutes(m: Int): String = when {
    m < 60 -> "$m min"
    m % 60 == 0 -> "${m / 60} h"
    else -> "${m / 60} h ${m % 60} min"
}
