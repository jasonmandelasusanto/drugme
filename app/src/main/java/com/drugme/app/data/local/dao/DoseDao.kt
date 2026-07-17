package com.drugme.app.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.drugme.app.data.local.entity.DoseEntity
import com.drugme.app.data.local.entity.MedicationEntity
import kotlinx.coroutines.flow.Flow

/** A dose plus the medication it belongs to — what both the UI and the notification need. */
data class DoseWithMedication(
    @Embedded val dose: DoseEntity,
    @Embedded(prefix = "med_") val medication: MedicationEntity,
)

@Dao
interface DoseDao {

    /**
     * Generation is idempotent by way of IGNORE against the unique (scheduleId, scheduledAt)
     * index. The healer re-runs generation on every pass, and re-inserting an existing
     * occurrence must be a no-op — using REPLACE here would wipe the user's TAKEN status
     * and re-alarm a dose they had already handled.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(doses: List<DoseEntity>): List<Long>

    @Transaction
    @Query(
        """
        SELECT d.*, m.id AS med_id, m.name AS med_name, m.rxcui AS med_rxcui,
               m.doseAmount AS med_doseAmount, m.doseUnit AS med_doseUnit,
               m.foodRelation AS med_foodRelation,
               m.stockAmount AS med_stockAmount,
               m.refillReminderDays AS med_refillReminderDays,
               m.refillNotifiedAt AS med_refillNotifiedAt,
               m.diseaseId AS med_diseaseId, m.diseaseName AS med_diseaseName,
               m.notes AS med_notes, m.isActive AS med_isActive,
               m.createdAt AS med_createdAt, m.updatedAt AS med_updatedAt
        FROM doses d
        INNER JOIN medications m ON m.id = d.medicationId
        WHERE d.localDate = :date
        ORDER BY COALESCE(d.snoozedUntil, d.scheduledAt) ASC
        """
    )
    fun observeForDate(date: String): Flow<List<DoseWithMedication>>

    @Transaction
    @Query(
        """
        SELECT d.*, m.id AS med_id, m.name AS med_name, m.rxcui AS med_rxcui,
               m.doseAmount AS med_doseAmount, m.doseUnit AS med_doseUnit,
               m.foodRelation AS med_foodRelation,
               m.stockAmount AS med_stockAmount,
               m.refillReminderDays AS med_refillReminderDays,
               m.refillNotifiedAt AS med_refillNotifiedAt,
               m.diseaseId AS med_diseaseId, m.diseaseName AS med_diseaseName,
               m.notes AS med_notes, m.isActive AS med_isActive,
               m.createdAt AS med_createdAt, m.updatedAt AS med_updatedAt
        FROM doses d
        INNER JOIN medications m ON m.id = d.medicationId
        WHERE d.id = :id
        """
    )
    suspend fun getWithMedication(id: String): DoseWithMedication?

    /**
     * The earliest dose still awaiting action at or after [afterMillis] — the single dose
     * the next alarm is armed for.
     *
     * Ordered by the *effective* time so a snoozed dose competes at its snooze target
     * rather than its original slot; otherwise snoozing a dose could leave the chain
     * armed for a time that has already passed.
     */
    @Query(
        """
        SELECT * FROM doses
        WHERE status = 'PENDING' AND COALESCE(snoozedUntil, scheduledAt) >= :afterMillis
        ORDER BY COALESCE(snoozedUntil, scheduledAt) ASC
        LIMIT 1
        """
    )
    suspend fun getNextPending(afterMillis: Long): DoseEntity?

    /** Doses whose grace window has closed with no user action. */
    @Query(
        """
        SELECT * FROM doses
        WHERE status = 'PENDING' AND COALESCE(snoozedUntil, scheduledAt) < :cutoffMillis
        """
    )
    suspend fun getOverdue(cutoffMillis: Long): List<DoseEntity>

    @Query("SELECT * FROM doses WHERE id = :id")
    suspend fun getById(id: String): DoseEntity?

    @Query("UPDATE doses SET status = :status, takenAt = :takenAt, snoozedUntil = NULL WHERE id = :id")
    suspend fun setStatus(id: String, status: String, takenAt: Long?)

    @Query("UPDATE doses SET snoozedUntil = :until WHERE id = :id")
    suspend fun setSnoozed(id: String, until: Long)

    @Query("UPDATE doses SET status = 'MISSED' WHERE id IN (:ids)")
    suspend fun markMissed(ids: List<String>)

    @Query("SELECT * FROM doses WHERE scheduleId = :scheduleId AND scheduledAt >= :fromMillis")
    suspend fun getFutureForSchedule(scheduleId: String, fromMillis: Long): List<DoseEntity>

    /**
     * Clears future *untouched* doses for a schedule, used when the schedule is edited so
     * stale occurrences don't linger. Deliberately spares anything the user already acted
     * on — rewriting a schedule must not erase the record of doses actually taken.
     */
    @Query(
        """
        DELETE FROM doses
        WHERE scheduleId = :scheduleId AND scheduledAt >= :fromMillis AND status = 'PENDING'
        """
    )
    suspend fun deleteFuturePending(scheduleId: String, fromMillis: Long)

    @Query("SELECT COUNT(*) FROM doses WHERE status = :status AND localDate BETWEEN :from AND :to")
    suspend fun countByStatusBetween(status: String, from: String, to: String): Int

    @Transaction
    @Query(
        """
        SELECT d.*, m.id AS med_id, m.name AS med_name, m.rxcui AS med_rxcui,
               m.doseAmount AS med_doseAmount, m.doseUnit AS med_doseUnit,
               m.foodRelation AS med_foodRelation,
               m.stockAmount AS med_stockAmount,
               m.refillReminderDays AS med_refillReminderDays,
               m.refillNotifiedAt AS med_refillNotifiedAt,
               m.diseaseId AS med_diseaseId, m.diseaseName AS med_diseaseName,
               m.notes AS med_notes, m.isActive AS med_isActive,
               m.createdAt AS med_createdAt, m.updatedAt AS med_updatedAt
        FROM doses d
        INNER JOIN medications m ON m.id = d.medicationId
        WHERE d.localDate BETWEEN :from AND :to
        ORDER BY d.scheduledAt DESC
        """
    )
    fun observeHistory(from: String, to: String): Flow<List<DoseWithMedication>>

    /** Latest generated occurrence for a schedule, so the window extends rather than restarts. */
    @Query("SELECT MAX(scheduledAt) FROM doses WHERE scheduleId = :scheduleId")
    suspend fun getLastGeneratedAt(scheduleId: String): Long?

    // --- Analytics ---------------------------------------------------------

    /**
     * Lateness of every dose actually taken in a window, in minutes.
     *
     * Signed on purpose: negative means taken early, which is a real behaviour and not the
     * same as being on time. Averaging absolute values would let a habit of "two hours
     * early, two hours late" report as perfectly punctual.
     */
    @Query(
        """
        SELECT (takenAt - scheduledAt) / 60000.0 FROM doses
        WHERE status = 'TAKEN' AND takenAt IS NOT NULL
          AND localDate BETWEEN :from AND :to
        """
    )
    suspend fun takenDelaysMinutes(from: String, to: String): List<Double>

    /**
     * Total amount taken per medication, in that medication's own unit.
     *
     * Summed from the medication's current doseAmount rather than a per-dose snapshot: the
     * app has no dose-amount history, so editing a dose retroactively changes past totals.
     * Acceptable for a personal tracker, and worth knowing when reading these numbers.
     */
    @Query(
        """
        SELECT m.id AS medicationId, m.name AS name, m.doseUnit AS unit,
               COUNT(*) AS doseCount, SUM(m.doseAmount) AS totalAmount
        FROM doses d
        INNER JOIN medications m ON m.id = d.medicationId
        WHERE d.status = 'TAKEN'
        GROUP BY m.id
        ORDER BY m.name COLLATE NOCASE ASC
        """
    )
    fun observeTotalsAllTime(): Flow<List<MedicationTotal>>

    @Query(
        """
        SELECT m.id AS medicationId, m.name AS name, m.doseUnit AS unit,
               COUNT(*) AS doseCount, SUM(m.doseAmount) AS totalAmount
        FROM doses d
        INNER JOIN medications m ON m.id = d.medicationId
        WHERE d.status = 'TAKEN' AND d.localDate BETWEEN :from AND :to
        GROUP BY m.id
        ORDER BY m.name COLLATE NOCASE ASC
        """
    )
    fun observeTotalsBetween(from: String, to: String): Flow<List<MedicationTotal>>

    /** Earliest recorded dose, used to scale "average per day" over the real tracking span. */
    @Query("SELECT MIN(localDate) FROM doses WHERE status = 'TAKEN'")
    suspend fun firstTakenDate(): String?
}

/** Aggregate of one medication's taken doses. [totalAmount] is in that medication's unit. */
data class MedicationTotal(
    val medicationId: String,
    val name: String,
    val unit: String,
    val doseCount: Int,
    val totalAmount: Double,
)
