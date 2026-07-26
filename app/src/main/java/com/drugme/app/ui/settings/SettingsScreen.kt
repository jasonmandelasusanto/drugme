package com.drugme.app.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drugme.app.notify.DoseNotifier
import com.drugme.app.data.update.AppUpdateState
import com.drugme.app.ui.components.SectionCard
import com.drugme.app.ui.onboarding.OemBatteryGuidance
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val reminderTimeFormat = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onFixExactAlarms: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val darkMode = state.darkMode ?: isSystemInDarkTheme()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.padding(inner).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                AppearanceSetting(
                    darkMode = darkMode,
                    onDarkModeChange = viewModel::setDarkMode,
                )
            }

            item {
                SectionCard(title = "Notifications & reminders") {
                    StatusRow(
                        label = "Notification permission",
                        detail = if (state.notificationsOk) "Allowed" else "Blocked or channel disabled",
                        ok = state.notificationsOk,
                    )
                    StatusRow(
                        label = "Exact reminder timing",
                        detail = if (state.exactAlarmsOk) "Available" else "Needs attention",
                        ok = state.exactAlarmsOk,
                    )
                    StatusRow(
                        label = "Background access",
                        detail = if (state.batteryOptimized) "Battery optimisation may delay reminders" else "Unrestricted",
                        ok = !state.batteryOptimized,
                    )

                    state.nextReminder?.let {
                        Text(
                            "Next reminder: ${it.atZone(ZoneId.systemDefault()).format(reminderTimeFormat)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = viewModel::sendTestReminder,
                            enabled = state.notificationsOk,
                        ) {
                            Icon(Icons.Default.Notifications, contentDescription = null)
                            Text(" Test reminder")
                        }
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                )
                            },
                        ) { Text("Permission") }
                    }
                    state.testResult?.let {
                        Text(
                            if (it) "Test reminder sent." else "Test failed. Check notification permission and channel settings.",
                            color = if (it) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (!state.exactAlarmsOk) {
                        OutlinedButton(onClick = onFixExactAlarms) { Text("Allow exact timing") }
                    }
                    if (state.batteryOptimized) {
                        OutlinedButton(
                            onClick = {
                                OemBatteryGuidance.openBestSettings(
                                    context,
                                    OemBatteryGuidance.forCurrentDevice(),
                                )
                            },
                        ) { Text("Review battery settings") }
                    }
                }
            }

            item {
                SectionCard(title = "Reminder sound & vibration") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.VolumeUp,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text("Managed by Android", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Choose the reminder sound, vibration pattern, and interruption level in the system notification channel.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    .putExtra(Settings.EXTRA_CHANNEL_ID, DoseNotifier.CHANNEL_REMINDERS)
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Open sound and vibration settings") }
                }
            }

            item {
                SectionCard(title = "Privacy") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Discreet notifications", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Hide medication names and dose details in notifications.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.discreetNotifications,
                            onCheckedChange = viewModel::setDiscreetNotifications,
                        )
                    }
                }
            }

            item {
                AppUpdateSetting(
                    state = state.appUpdate,
                    currentVersion = state.currentVersion,
                    updatesSupported = state.updatesSupported,
                    onCheck = viewModel::checkForUpdates,
                    onInstall = {
                        if (!viewModel.installUpdate()) {
                            context.startActivity(viewModel.unknownSourcesIntent())
                        }
                    },
                )
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (state.healthy) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        if (state.healthy) "Reminder diagnostics look healthy."
                        else "One or more reminder settings need attention.",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun AppUpdateSetting(
    state: AppUpdateState,
    currentVersion: String,
    updatesSupported: Boolean,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
) {
    SectionCard(title = "App updates") {
        Text(
            "Installed version $currentVersion",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            when {
                !updatesSupported -> "Update checks are disabled in debug builds."
                state.checking -> "Checking GitHub Releases…"
                state.downloading -> "Downloading and verifying version ${state.version}…"
                state.downloaded -> "Version ${state.version} is downloaded and signature-verified."
                state.error != null -> state.error
                state.upToDate -> "You’re using the latest version."
                state.available -> "Version ${state.version} is available."
                else -> "DrugMe checks GitHub Releases daily. You can also check now."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.error != null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.checking || state.downloading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (state.downloaded) {
            FilledTonalButton(
                onClick = onInstall,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Review and install")
            }
        } else {
            OutlinedButton(
                onClick = onCheck,
                enabled = updatesSupported && !state.checking && !state.downloading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Check for updates")
            }
        }
    }
}

@Composable
internal fun AppearanceSetting(
    darkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
) {
    SectionCard(title = "Appearance") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (darkMode) Icons.Default.DarkMode else Icons.Default.LightMode,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text("Light / dark mode", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Choose the color scheme used throughout DrugMe.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Light",
                        color = if (!darkMode) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = darkMode,
                        onCheckedChange = onDarkModeChange,
                        modifier = Modifier.semantics { contentDescription = "Dark mode" },
                    )
                    Text(
                        "Dark",
                        color = if (darkMode) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, detail: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (ok) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
            contentDescription = if (ok) "$label ready" else "$label needs attention",
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
