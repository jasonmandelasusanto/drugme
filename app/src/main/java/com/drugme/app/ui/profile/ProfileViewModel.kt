package com.drugme.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drugme.app.data.auth.AuthRepository
import com.drugme.app.data.auth.AuthUser
import com.drugme.app.data.local.dao.MedicationTotal
import com.drugme.app.data.local.dao.MedicationWithSchedules
import com.drugme.app.data.repo.AccountDeleter
import com.drugme.app.data.repo.DoseRepository
import com.drugme.app.data.repo.MedicationRepository
import com.drugme.app.data.prefs.SettingsRepository
import com.drugme.app.data.sync.SyncEngine
import com.drugme.app.domain.model.DoseStatus
import com.drugme.app.domain.schedule.Forecast
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How reliably doses are taken at the time they were due.
 *
 * Separate from adherence on purpose: taking every dose four hours late is 100% adherent
 * and still wrong for a drug with a dosing interval. Adherence answers "did you take it",
 * punctuality answers "when".
 */
data class Punctuality(
    val onTimeCount: Int = 0,
    val lateCount: Int = 0,
    val earlyCount: Int = 0,
    val medianDelayMinutes: Int? = null,
) {
    val total: Int get() = onTimeCount + lateCount + earlyCount

    /** Null rather than 0% when nothing has been taken — "no data" isn't "you failed". */
    val onTimePercent: Int? get() = if (total == 0) null else (onTimeCount * 100) / total
}

data class Adherence(val taken: Int = 0, val missed: Int = 0, val skipped: Int = 0) {
    val decided: Int get() = taken + missed + skipped
    val takenPercent: Int? get() = if (decided == 0) null else (taken * 100) / decided
}

/** One medication's consumption, in its own unit. */
data class MedicationUsage(
    val name: String,
    val unit: String,
    val totalAmount: Double,
    val totalDoses: Int,
    val weekAmount: Double,
    val weekDoses: Int,
    val averagePerDay: Double,
)

data class ProfileState(
    val user: AuthUser? = null,
    val adherence: Adherence = Adherence(),
    val punctuality: Punctuality = Punctuality(),
    val usage: List<MedicationUsage> = emptyList(),
    val medications: List<MedicationWithSchedules> = emptyList(),
    val medicationPreview: List<MedicationWithSchedules> = emptyList(),
    val medicationRelevance: Map<String, String> = emptyMap(),
    val hiddenOverdueDoses: Int = 0,
    val forecasts: Map<String, Forecast> = emptyMap(),
    val trackingSinceDays: Int = 0,
    val deleting: Boolean = false,
    val deleted: Boolean = false,
    val error: String? = null,
    val discreetNotifications: Boolean = false,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val doseRepository: DoseRepository,
    private val medicationRepository: MedicationRepository,
    private val syncEngine: SyncEngine,
    private val accountDeleter: AccountDeleter,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {

    private val extras = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = extras.asStateFlow()

    init {
        observe()
    }

    private fun observe() {
        viewModelScope.launch {
            combine(
                auth.authState,
                doseRepository.observeTotalsAllTime(),
                doseRepository.observeTotalsBetween(weekStart(), today()),
                medicationRepository.observeAll(),
                settings.discreetNotifications,
            ) { user, allTime, week, meds, discreet ->
                Penta(user, allTime, week, meds, discreet)
            }.combine(doseRepository.observeForDate(today())) { profile, doses ->
                profile to doses
            }.collect { (profile, doses) ->
                val (user, allTime, week, meds, discreet) = profile
                val trackingDays = trackingSpanDays()
                val now = clock.instant()
                val pending = doses.filter { it.dose.status == DoseStatus.PENDING }
                val overdueMedicationIds = pending
                    .filter { it.dose.effectiveAt.isBefore(now) }
                    .sortedBy { it.dose.effectiveAt }
                    .map { it.medication.id }
                    .distinct()
                val upcomingMedicationIds = pending
                    .filterNot { it.dose.effectiveAt.isBefore(now) }
                    .sortedBy { it.dose.effectiveAt }
                    .map { it.medication.id }
                    .distinct()
                val priorityIds = (overdueMedicationIds + upcomingMedicationIds +
                    meds.filter { it.medication.isActive }.map { it.medication.id } +
                    meds.map { it.medication.id }).distinct()
                val previewIds = priorityIds.take(3)
                extras.value = extras.value.copy(
                    user = user,
                    medications = meds,
                    usage = buildUsage(allTime, week, trackingDays),
                    trackingSinceDays = trackingDays,
                    adherence = adherence(),
                    punctuality = punctuality(),
                    forecasts = meds.mapNotNull { m ->
                        medicationRepository.forecast(m.medication.id)?.let { m.medication.id to it }
                    }.toMap(),
                    discreetNotifications = discreet,
                    medicationPreview = previewIds.mapNotNull { id ->
                        meds.firstOrNull { it.medication.id == id }
                    },
                    medicationRelevance = buildMap {
                        overdueMedicationIds.forEach { put(it, "Overdue") }
                        upcomingMedicationIds.filterNot(::containsKey).forEach { put(it, "Upcoming") }
                    },
                    hiddenOverdueDoses = pending.count {
                        it.dose.effectiveAt.isBefore(now) && it.medication.id !in previewIds
                    },
                )
            }
        }
    }

    private fun buildUsage(
        allTime: List<MedicationTotal>,
        week: List<MedicationTotal>,
        trackingDays: Int,
    ): List<MedicationUsage> {
        val weekBy = week.associateBy { it.medicationId }
        return allTime.map { t ->
            val w = weekBy[t.medicationId]
            MedicationUsage(
                name = t.name,
                unit = t.unit,
                totalAmount = t.totalAmount,
                totalDoses = t.doseCount,
                weekAmount = w?.totalAmount ?: 0.0,
                weekDoses = w?.doseCount ?: 0,
                // Averaged over the real tracking span, not a fixed 7 or 30. Dividing a
                // three-day history by 30 would report a daily dose six times lower than
                // reality and make the number worse than useless.
                averagePerDay = if (trackingDays > 0) t.totalAmount / trackingDays else 0.0,
            )
        }
    }

    private suspend fun trackingSpanDays(): Int {
        val first = doseRepository.firstTakenDate() ?: return 0
        return (ChronoUnit.DAYS.between(LocalDate.parse(first), LocalDate.now(clock)).toInt() + 1)
            .coerceAtLeast(1)
    }

    private suspend fun adherence(): Adherence {
        val from = LocalDate.now(clock).minusDays(29)
        val to = LocalDate.now(clock)
        return Adherence(
            taken = doseRepository.countBetween(DoseStatus.TAKEN, from, to),
            missed = doseRepository.countBetween(DoseStatus.MISSED, from, to),
            skipped = doseRepository.countBetween(DoseStatus.SKIPPED, from, to),
        )
    }

    private suspend fun punctuality(): Punctuality {
        val from = LocalDate.now(clock).minusDays(29)
        val delays = doseRepository.takenDelays(from, LocalDate.now(clock))
        if (delays.isEmpty()) return Punctuality()

        // ±30 minutes counts as on time. Tight enough to mean something, loose enough that
        // nobody is marked late for taking a pill twenty minutes after the alarm — which is
        // normal behaviour, not a failure.
        val onTime = delays.count { abs(it) <= ON_TIME_MINUTES }
        val late = delays.count { it > ON_TIME_MINUTES }
        val early = delays.count { it < -ON_TIME_MINUTES }

        // Median, not mean: one dose taken eight hours late would drag an average into
        // nonsense while the typical day was fine.
        val sorted = delays.sorted()
        val median = sorted[sorted.size / 2]

        return Punctuality(
            onTimeCount = onTime,
            lateCount = late,
            earlyCount = early,
            medianDelayMinutes = median.roundToInt(),
        )
    }

    fun signOut() {
        viewModelScope.launch { auth.signOut() }
    }

    fun setDiscreetNotifications(enabled: Boolean) {
        viewModelScope.launch { settings.setDiscreetNotifications(enabled) }
    }

    fun setMedicationActive(id: String, active: Boolean) {
        viewModelScope.launch { medicationRepository.setActive(id, active) }
    }

    fun setStock(id: String, amount: Double?) {
        viewModelScope.launch { medicationRepository.setStock(id, amount) }
    }

    /**
     * Deletes a single medication. Cascades to its schedules and dose history — see
     * MedicationRepository.delete. The list refreshes on its own via observe().
     *
     * The remote tombstone is essential, not optional: a plain local delete would be
     * resurrected on the next sync, because the record still exists in Firestore and pull()
     * would treat it as a new medication to download. markDeleted no-ops when signed out.
     */
    fun deleteMedication(id: String) {
        viewModelScope.launch {
            medicationRepository.delete(id)
            syncEngine.markDeleted(id)
        }
    }

    /**
     * Deletes the account and everything in it.
     *
     * Irreversible and total — see AccountDeleter for what that actually covers.
     */
    fun deleteAccount() {
        extras.value = extras.value.copy(deleting = true, error = null)
        viewModelScope.launch {
            accountDeleter.deleteEverything()
                .onSuccess { extras.value = extras.value.copy(deleting = false, deleted = true) }
                .onFailure {
                    extras.value = extras.value.copy(
                        deleting = false,
                        error = it.message ?: "Could not delete account",
                    )
                }
        }
    }

    private fun today() = LocalDate.now(clock)
    private fun weekStart() = LocalDate.now(clock).minusDays(6)

    private data class Penta<A, B, C, D, E>(
        val a: A,
        val b: B,
        val c: C,
        val d: D,
        val e: E,
    )

    private companion object {
        const val ON_TIME_MINUTES = 30.0
    }
}
