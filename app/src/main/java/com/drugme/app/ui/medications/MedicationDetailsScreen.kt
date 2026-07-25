package com.drugme.app.ui.medications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drugme.app.data.medical.MedicationInfoOutcome
import com.drugme.app.data.medical.MedicationInformation
import com.drugme.app.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationDetailsScreen(
    medicationId: String,
    onNavigateBack: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: MedicationDetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(medicationId) { viewModel.load(medicationId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.medication?.medication?.name ?: "Medication details") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onEdit(medicationId) }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit medication")
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
            state.medication?.medication?.let { med ->
                item {
                    SectionCard(title = "Your prescribed dose") {
                        Text(
                            med.doseUnit.format(med.doseAmount),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "This is the dose you entered in DrugMe. Official labeling below is general information and does not replace your prescription.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            when {
                state.loading -> item { CircularProgressIndicator() }
                state.information is MedicationInfoOutcome.Available -> {
                    val outcome = state.information as MedicationInfoOutcome.Available
                    val info = outcome.information
                    if (outcome.stale) {
                        item {
                            Text(
                                "Offline: showing recently cached information.",
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                    informationSections(info).forEach { (title, body) ->
                        item { InformationSection(title, body) }
                    }
                    item {
                        SectionCard(title = "Sources") {
                            info.sources.forEach { source ->
                                Text(source.organization, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${source.scope} · ${source.country}" +
                                        source.lastUpdated?.let { " · Updated $it" }.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = { uriHandler.openUri(source.url) }) {
                                    Text("Open complete official information")
                                }
                            }
                        }
                    }
                }
                state.information is MedicationInfoOutcome.Offline -> item {
                    InformationUnavailable(
                        "You're offline and no recent official information is cached.",
                        onRetry = { viewModel.load(medicationId, forceRefresh = true) },
                    )
                }
                state.information is MedicationInfoOutcome.Error -> item {
                    InformationUnavailable(
                        (state.information as MedicationInfoOutcome.Error).message,
                        onRetry = { viewModel.load(medicationId, forceRefresh = true) },
                    )
                }
                else -> item {
                    InformationUnavailable(
                        "Official information is unavailable for this medication.",
                        onRetry = { viewModel.load(medicationId, forceRefresh = true) },
                    )
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Text(
                        "Educational information only. Do not change or stop a medication without advice from your prescriber or pharmacist.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private fun informationSections(info: MedicationInformation): List<Pair<String, String>> =
    listOfNotNull(
        info.whatItIs?.let { "What this medication is" to it },
        info.commonUses?.let { "Common uses" to it },
        info.commonSideEffects?.let { "Side effects" to it },
        info.seriousWarnings?.let { "Serious warnings" to it },
        info.contraindications?.let { "Contraindications and precautions" to it },
        info.officialDosage?.let { "Official dosage information" to it },
        info.dosageFormsAndStrengths?.let { "Dosage forms and strengths" to it },
        info.drugClassification?.let { "Drug classification" to it },
    )

@Composable
private fun InformationSection(title: String, body: String) {
    SectionCard(title = title) {
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InformationUnavailable(message: String, onRetry: () -> Unit) {
    Column {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRetry) { Text("Try again") }
    }
}
