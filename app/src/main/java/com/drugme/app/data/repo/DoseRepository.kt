package com.drugme.app.data.repo

import androidx.room.withTransaction
import com.drugme.app.data.local.DrugMeDatabase
import com.drugme.app.data.local.dao.DoseDao
import com.drugme.app.data.local.dao.DoseWithMedication
import com.drugme.app.data.local.dao.MedicationDao
import com.drugme.app.data.local.dao.MedicationTotal
import com.drugme.app.data.local.dao.ScheduleDao
import com.drugme.app.data.local.entity.DoseEntity
import com.drugme.app.domain.model.DoseStatus
import com.drugme.app.domain.schedule.DoseOccurrenceGenerator
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DoseRepository @Inject constructor(
    private val db: DrugMeDatabase,
    private val doseDao: DoseDao,
    private val medicationDao: MedicationDao,
    private val scheduleDao: ScheduleDao,
    private val generator: DoseOccurrenceGenerator,
    private val clock: Clock,
) {

    fun observeForDate(date: LocalDate): Flow<List<DoseWithMedication>> =
        doseDao.observeForDate(date.toString())

    fun observeToday(): Flow<List<DoseWithMedication>> =
        observeForDate(LocalDate.now(clock))

    fun observeHistory(from: LocalDate, to: LocalDate): Flow<List<DoseWithMedication>> =
        doseDao.observeHistory(from.toString(), to.toString())

    suspend fun getWithMedication(doseId: String): DoseWithMedication? =
        doseDao.getWithMedication(doseId)

    /**
     * Materialises dose rows for every active schedule across a rolling window.
     *
     * Safe to call at any time from anywhere: inserts are IGNORE-on-conflict against the
     * unique (scheduleId, scheduledAt) index, so re-running never duplicates an occurrence
     * nor disturbs one the user has already acted on. That property is what lets the boot
     * receiver, the healer worker and app startup all call it unconditionally without
     * coordinating.
     *
     * @return how many new occurrences were created.
     */
    suspend fun materializeWindow(days: Long = WINDOW_DAYS): Int {
        val zone = clock.zone
        val today = LocalDate.now(clock)
        val horizon = today.plusDays(days)

        var created = 0
        for (schedule in scheduleDao.getGeneratable(today.toString())) {
            // Start from today rather than the last generated dose: backfilling is what
            // lets a dose the user hasn't seen yet still appear after a long gap, and the
            // unique index absorbs the overlap.
            val occurrences = generator.generate(schedule, today, horizon, zone)
            if (occurrences.isEmpty()) continue

            val rows = occurrences.map { o ->
                DoseEntity(
                    id = UUID.randomUUID().toString(),
                    medicationId = schedule.medicationId,
                    scheduleId = schedule.id,
                    scheduledAt = o.scheduledAt,
                    localDate = o.localDate,
                    status = DoseStatus.PENDING,
                )
            }
            created += doseDao.insertIgnore(rows).count { it != -1L }
        }
        return created
    }

    /** Drops not-yet-acted-on future doses for a schedule; used when the schedule itself changes. */
    suspend fun clearFuturePending(scheduleId: String) {
        doseDao.deleteFuturePending(scheduleId, clock.instant().toEpochMilli())
    }

    suspend fun getNextPending(after: Instant = clock.instant()): DoseEntity? =
        doseDao.getNextPending(after.toEpochMilli())

    /**
     * Every dose due right now and still pending — what the alarm posts reminders for.
     *
     * The alarm chain arms one dose, but medications sharing a time all come due together;
     * notifying the whole due set is what makes every one of them appear rather than just
     * the armed one.
     */
    suspend fun getDuePending(now: Instant = clock.instant()): List<DoseWithMedication> =
        doseDao.getDuePendingWithMedication(now.toEpochMilli())

    suspend fun markTaken(doseId: String) = setStatus(doseId, DoseStatus.TAKEN)

    suspend fun markSkipped(doseId: String) = setStatus(doseId, DoseStatus.SKIPPED)

    /**
     * Returns a dose to PENDING, undoing a taken/skipped/missed mark.
     *
     * People tap the wrong button, or mark a dose taken and then get interrupted before
     * actually taking it. Without a way back the record silently diverges from reality —
     * and an adherence history that can't be corrected is worse than none, because it still
     * looks authoritative.
     */
    suspend fun markPending(doseId: String) = setStatus(doseId, DoseStatus.PENDING)

    /**
     * Sets a dose's status and keeps stock in step.
     *
     * Stock moves on the *transition*, not the destination: going to TAKEN consumes a dose,
     * leaving TAKEN gives it back. Without that symmetry, undoing a mis-tap would silently
     * lose stock, and toggling taken/untaken a few times would drain a month's supply on
     * paper while the real bottle sat untouched.
     *
     * Done in one transaction so a crash can't leave the dose marked taken with the stock
     * unchanged, or vice versa.
     */
    suspend fun setStatus(doseId: String, status: DoseStatus) {
        db.withTransaction {
            val current = doseDao.getById(doseId) ?: return@withTransaction
            if (current.status == status) return@withTransaction

            val medication = medicationDao.getMedication(current.medicationId)
            val perDose = medication?.doseAmount ?: 0.0
            val wasTaken = current.status == DoseStatus.TAKEN
            val nowTaken = status == DoseStatus.TAKEN

            doseDao.setStatus(
                doseId,
                status.name,
                takenAt = if (nowTaken) clock.millis() else null,
            )

            val delta = when {
                !wasTaken && nowTaken -> -perDose
                wasTaken && !nowTaken -> +perDose
                else -> 0.0
            }
            if (delta != 0.0 && medication != null) {
                medicationDao.adjustStock(medication.id, delta, clock.millis())
                // Stock went up, so a previously-sent low-stock warning may no longer
                // apply; let it fire again if the level drops back.
                if (delta > 0) medicationDao.clearRefillNotified(medication.id)
            }
        }
    }

    suspend fun snooze(doseId: String, by: Duration = SNOOZE): Instant {
        val until = clock.instant().plus(by)
        doseDao.setSnoozed(doseId, until.toEpochMilli())
        return until
    }

    /**
     * Ages out doses the user never acted on.
     *
     * Only the system assigns MISSED, and only after the grace window — the distinction
     * from SKIPPED (an explicit decision) is what keeps adherence history honest.
     *
     * @return ids newly marked missed.
     */
    suspend fun markOverdueAsMissed(grace: Duration = GRACE): List<String> {
        val cutoff = clock.instant().minus(grace)
        val overdue = doseDao.getOverdue(cutoff.toEpochMilli())
        if (overdue.isEmpty()) return emptyList()
        val ids = overdue.map { it.id }
        doseDao.markMissed(ids)
        return ids
    }

    suspend fun countBetween(status: DoseStatus, from: LocalDate, to: LocalDate): Int =
        doseDao.countByStatusBetween(status.name, from.toString(), to.toString())

    // --- analytics ---

    /** Signed lateness in minutes for every dose taken in the window; negative = early. */
    suspend fun takenDelays(from: LocalDate, to: LocalDate): List<Double> =
        doseDao.takenDelaysMinutes(from.toString(), to.toString())

    fun observeTotalsAllTime(): Flow<List<MedicationTotal>> = doseDao.observeTotalsAllTime()

    fun observeTotalsBetween(from: LocalDate, to: LocalDate): Flow<List<MedicationTotal>> =
        doseDao.observeTotalsBetween(from.toString(), to.toString())

    /** Date of the first dose ever taken, used to scale "average per day" honestly. */
    suspend fun firstTakenDate(): String? = doseDao.firstTakenDate()

    companion object {
        /**
         * How far ahead doses are materialised. Long enough that the app survives weeks
         * without being opened, short enough that editing a schedule doesn't rewrite a
         * year of rows.
         */
        const val WINDOW_DAYS = 14L
        val SNOOZE: Duration = Duration.ofMinutes(10)

        /** How long a dose stays actionable before it counts as missed. */
        val GRACE: Duration = Duration.ofHours(4)
    }
}
