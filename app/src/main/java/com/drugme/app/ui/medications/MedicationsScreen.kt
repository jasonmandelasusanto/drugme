package com.drugme.app.ui.medications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationsScreen(
    onAddMedication: () -> Unit,
    onEditMedication: (String) -> Unit,
    onOpenDetails: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: MedicationsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Medications") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    label = { Text("Search medications or conditions") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MedicationFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = state.filter == filter,
                            onClick = { viewModel.setFilter(filter) },
                            label = {
                                Text(
                                    when (filter) {
                                        MedicationFilter.ALL -> "All (${state.allCount})"
                                        MedicationFilter.ACTIVE -> "Active"
                                        MedicationFilter.DISCONTINUED -> "Discontinued"
                                    }
                                )
                            },
                        )
                    }
                }
            }
            items(state.medications, key = { it.medication.id }) { item ->
                val medication = item.medication
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 88.dp)
                        .semantics {
                            contentDescription =
                                "${medication.name}, ${if (medication.isActive) "active" else "discontinued"}"
                        }
                        .clickable { onOpenDetails(medication.id) },
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                medication.name.replaceFirstChar(Char::uppercase),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                medication.doseUnit.format(medication.doseAmount),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            medication.diseaseName?.let {
                                Text(
                                    "For $it",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (medication.rxcui != null) {
                                AssistChip(
                                    onClick = { onOpenDetails(medication.id) },
                                    label = { Text("Verified information available") },
                                )
                            }
                        }
                        IconButton(
                            onClick = { viewModel.setActive(medication.id, !medication.isActive) },
                        ) {
                            Icon(
                                if (medication.isActive) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                                contentDescription =
                                    if (medication.isActive) "Discontinue ${medication.name}"
                                    else "Resume ${medication.name}",
                            )
                        }
                        Spacer(Modifier.width(2.dp))
                        IconButton(onClick = { onEditMedication(medication.id) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit ${medication.name}")
                        }
                    }
                }
            }
            if (state.medications.isEmpty()) {
                item {
                    Text(
                        if (state.allCount == 0) "No medications yet. Add one to create a reminder."
                        else "No medications match these filters.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
