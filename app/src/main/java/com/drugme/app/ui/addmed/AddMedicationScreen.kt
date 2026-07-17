package com.drugme.app.ui.addmed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drugme.app.domain.model.DiseaseRef
import com.drugme.app.domain.model.DoseUnit
import com.drugme.app.domain.model.ScheduleType
import com.drugme.app.ui.components.SectionCard
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMedicationScreen(
    medicationId: String?,
    onNavigateBack: () -> Unit,
    viewModel: AddMedicationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(medicationId) {
        if (medicationId != null) viewModel.load(medicationId)
    }
    LaunchedEffect(state.saved) {
        if (state.saved) onNavigateBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (medicationId == null) "Add medication" else "Edit medication") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::save, enabled = state.canSave && !state.saving) {
                        Text("Save")
                    }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.padding(inner).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { DrugNameSection(state, viewModel) }
            item { DoseSection(state, viewModel) }
            item { ScheduleSection(state, viewModel) }
            item { DurationSection(state, viewModel) }
            item { NotesSection(state, viewModel) }
            state.error?.let { err ->
                item {
                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun DrugNameSection(state: AddMedicationState, vm: AddMedicationViewModel) {
    SectionCard(title = "Medication") {
        OutlinedTextField(
            value = state.name,
            onValueChange = vm::onNameChange,
            label = { Text("Name") },
            placeholder = { Text("e.g. Metformin") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.showSuggestions && state.suggestions.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    state.suggestions.take(6).forEach { s ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.onSuggestionPicked(s) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                s.name.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            // Surfacing the alias is what makes the jump from the typed word
                            // to a different stored name intelligible: someone typing
                            // "paracetamol" and getting "acetaminophen" needs to see why.
                            s.matchedAlias?.let { alias ->
                                Text(
                                    "also known as ${alias.replaceFirstChar { c -> c.uppercase() }}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (s.diseases.isNotEmpty()) {
                                Text(
                                    s.diseases.take(2).joinToString(" · ") { it.name },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                    TextButton(onClick = vm::dismissSuggestions, modifier = Modifier.padding(4.dp)) {
                        Text("Use what I typed")
                    }
                }
            }
        }

        if (state.availableDiseases.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Treating", style = MaterialTheme.typography.titleMedium)
            Text(
                // Wording matters: this is reference data from RxNorm/MED-RT, not advice.
                "Indications listed for this drug in RxNorm. Pick the one that applies to you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            DiseaseChips(state.availableDiseases, state.selectedDisease, vm::onDiseaseSelected)
        }
    }
}

@Composable
private fun DiseaseChips(
    diseases: List<DiseaseRef>,
    selected: DiseaseRef?,
    onSelect: (DiseaseRef?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // The generator sorts most-specific-first, so the leading few are the recognisable
    // ones; the rest stay one tap away rather than flooding the form.
    val shown = if (expanded) diseases else diseases.take(4)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.forEach { d ->
            FilterChip(
                selected = selected?.id == d.id,
                onClick = { onSelect(if (selected?.id == d.id) null else d) },
                label = { Text(d.name) },
            )
        }
        if (diseases.size > 4) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Show fewer" else "Show ${diseases.size - 4} more")
            }
        }
    }
}

@Composable
private fun DoseSection(state: AddMedicationState, vm: AddMedicationViewModel) {
    SectionCard(title = "Dose") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.doseAmount,
                onValueChange = vm::onDoseAmountChange,
                label = { Text("Amount") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(120.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                state.doseUnit.format(state.doseAmountValue ?: 0.0),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text("Unit", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        UnitChips(state.doseUnit, vm::onDoseUnitChange)
    }
}

@Composable
private fun UnitChips(selected: DoseUnit, onSelect: (DoseUnit) -> Unit) {
    var showAll by remember { mutableStateOf(false) }
    val units = if (showAll) DoseUnit.entries else DoseUnit.COMMON

    Column {
        units.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { u ->
                    FilterChip(
                        selected = selected == u,
                        onClick = { onSelect(u) },
                        label = { Text(u.label) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        TextButton(onClick = { showAll = !showAll }) {
            Text(if (showAll) "Common units only" else "More units")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleSection(state: AddMedicationState, vm: AddMedicationViewModel) {
    SectionCard(title = "Schedule") {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ScheduleType.entries.forEachIndexed { i, type ->
                SegmentedButton(
                    selected = state.scheduleType == type,
                    onClick = { vm.onScheduleTypeChange(type) },
                    shape = SegmentedButtonDefaults.itemShape(i, ScheduleType.entries.size),
                ) {
                    Text(
                        when (type) {
                            ScheduleType.TIMES_PER_DAY -> "Daily"
                            ScheduleType.DAYS_OF_WEEK -> "Weekdays"
                            ScheduleType.INTERVAL_DAYS -> "Interval"
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        when (state.scheduleType) {
            ScheduleType.TIMES_PER_DAY -> Unit
            ScheduleType.DAYS_OF_WEEK -> {
                Text("On these days", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        FilterChip(
                            selected = day in state.weekdays,
                            onClick = { vm.onWeekdayToggled(day) },
                            label = {
                                Text(day.getDisplayName(TextStyle.NARROW, Locale.getDefault()))
                            },
                        )
                    }
                }
                if (state.weekdays.isEmpty) {
                    Text(
                        "Pick at least one day.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            ScheduleType.INTERVAL_DAYS -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Every", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = state.intervalDays.toString(),
                        onValueChange = { vm.onIntervalChange(it.toIntOrNull() ?: 1) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(80.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("days", style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        Text("At these times", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        state.timesOfDay.forEachIndexed { i, t ->
            TimeRow(
                time = t,
                canRemove = state.timesOfDay.size > 1,
                onChange = { vm.onTimeChanged(i, it) },
                onRemove = { vm.onRemoveTime(i) },
            )
        }
        OutlinedButton(onClick = vm::onAddTime) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add time")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeRow(
    time: LocalTime,
    canRemove: Boolean,
    onChange: (LocalTime) -> Unit,
    onRemove: () -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        AssistChip(
            onClick = { showPicker = true },
            label = { Text(time.format(timeFmt), style = MaterialTheme.typography.bodyLarge) },
        )
        Spacer(Modifier.width(8.dp))
        if (canRemove) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = "Remove time")
            }
        }
    }

    if (showPicker) {
        val pickerState = rememberTimePickerState(
            initialHour = time.hour,
            initialMinute = time.minute,
            is24Hour = true,
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange(LocalTime.of(pickerState.hour, pickerState.minute))
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            },
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TimePicker(state = pickerState)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DurationSection(state: AddMedicationState, vm: AddMedicationViewModel) {
    SectionCard(title = "Duration") {
        DateRow(
            label = "Starts",
            date = state.startDate,
            onPick = vm::onStartDateChange,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Until", modifier = Modifier.width(64.dp), style = MaterialTheme.typography.bodyLarge)
            if (state.endDate == null) {
                OutlinedButton(onClick = { vm.onEndDateChange(state.startDate.plusDays(30)) }) {
                    Text("Set an end date")
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "Ongoing",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                DatePickerChip(state.endDate, onPick = { vm.onEndDateChange(it) })
                IconButton(onClick = { vm.onEndDateChange(null) }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear end date")
                }
            }
        }
        if (state.endDate != null && state.endDate.isBefore(state.startDate)) {
            Text(
                "The end date can't be before the start date.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DateRow(label: String, date: LocalDate, onPick: (LocalDate) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(64.dp), style = MaterialTheme.typography.bodyLarge)
        DatePickerChip(date, onPick)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerChip(date: LocalDate, onPick: (LocalDate) -> Unit) {
    var show by remember { mutableStateOf(false) }

    AssistChip(onClick = { show = true }, label = { Text(date.format(dateFmt)) })

    if (show) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        // The picker reports UTC midnight; read it back in UTC so the date
                        // can't shift by a day for users behind or ahead of it.
                        onPick(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    show = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun NotesSection(state: AddMedicationState, vm: AddMedicationViewModel) {
    SectionCard(title = "Notes") {
        OutlinedTextField(
            value = state.notes,
            onValueChange = vm::onNotesChange,
            placeholder = { Text("e.g. take with food") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
    }
}
