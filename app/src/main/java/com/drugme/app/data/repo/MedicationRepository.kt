package com.drugme.app.data.repo

import androidx.room.withTransaction
import com.drugme.app.data.local.DrugMeDatabase
import com.drugme.app.data.local.dao.MedicationDao
import com.drugme.app.data.local.dao.MedicationWithSchedules
import com.drugme.app.data.local.dao.ScheduleDao
import com.drugme.app.data.local.entity.MedicationEntity
import com.drugme.app.data.local.entity.ScheduleEntity
import com.drugme.app.alarm.DoseAlarmScheduler
import com.drugme.app.domain.schedule.Forecast
import com.drugme.app.domain.schedule.StockForecast
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MedicationRepository @Inject constructor(
    private val db: DrugMeDatabase,
    private val medicationDao: MedicationDao,
    private val scheduleDao: ScheduleDao,
    private val doseRepository: DoseRepository,
    private val alarmScheduler: DoseAlarmScheduler,
    private val stockForecast: StockForecast,
    private val clock: Clock,
) {

    fun observeActive(): Flow<List<MedicationWithSchedules>> = medicationDao.observeActive()
    fun observeAll(): Flow<List<MedicationWithSchedules>> = medicationDao.observeAll()
    fun observeById(id: String): Flow<MedicationWithSchedules?> = medicationDao.observeById(id)
    suspend fun getById(id: String): MedicationWithSchedules? = medicationDao.getById(id)

    /**
     * Creates or replaces a medication and its schedules, then rebuilds the affected doses
     * and re-arms the alarm.
     *
     * The write is a single transaction so a medication can never be persisted without its
     * schedules — a half-saved medication would sit in the list generating no reminders,
     * looking for all the world like it was set up correctly.
     */
    suspend fun save(medication: MedicationEntity, schedules: List<ScheduleEntity>) {
        db.withTransaction {
            medicationDao.upsert(medication)

            val existing = scheduleDao.getForMedication(medication.id)
            val keptIds = schedules.map { it.id }.toSet()
            existing.filter { it.id !in keptIds }.forEach { scheduleDao.delete(it.id) }

            scheduleDao.upsertAll(schedules)

            // Drop future PENDING doses so edits take effect immediately; doses already
            // taken or skipped are left alone so history stays truthful.
            schedules.forEach { doseRepository.clearFuturePending(it.id) }
        }

        doseRepository.materializeWindow()
        alarmScheduler.rescheduleNext()
    }

    /** Pauses a medication: stops future doses, keeps history. */
    suspend fun setActive(id: String, active: Boolean) {
        medicationDao.setActive(id, active, clock.millis())
        if (active) {
            doseRepository.materializeWindow()
        } else {
            scheduleDao.getForMedication(id).forEach { doseRepository.clearFuturePending(it.id) }
        }
        alarmScheduler.rescheduleNext()
    }

    /** Hard delete. Cascades to schedules and doses, including history. */
    suspend fun delete(id: String) {
        medicationDao.delete(id)
        alarmScheduler.rescheduleNext()
    }

    /** Sets stock directly — a refill, or a correction after counting the packet. */
    suspend fun setStock(id: String, amount: Double?) {
        val existing = medicationDao.getMedication(id) ?: return
        medicationDao.upsert(existing.copy(
            stockAmount = amount,
            // Refilling clears the warning so it can fire again next time stock runs low.
            refillNotifiedAt = null,
            updatedAt = clock.instant(),
        ))
    }

    /** Current run-out forecast for one medication, or null if stock isn't tracked. */
    suspend fun forecast(id: String): Forecast? {
        val item = medicationDao.getById(id) ?: return null
        return stockForecast.forecast(item, LocalDate.now(clock), clock.zone)
    }

    /**
     * Medications low enough on stock to warrant a warning, excluding any already warned
     * about.
     *
     * The refillNotifiedAt guard is what stops this nagging daily for the whole week before
     * a run-out. One warning per low-stock episode; it resets when stock goes back up.
     */
    suspend fun dueForRefillWarning(): List<Pair<MedicationWithSchedules, Forecast>> {
        val today = LocalDate.now(clock)
        return medicationDao.getActive().mapNotNull { item ->
            if (item.medication.refillNotifiedAt != null) return@mapNotNull null
            val f = stockForecast.forecast(item, today, clock.zone) ?: return@mapNotNull null
            if (f.needsRefillWarning) item to f else null
        }
    }

    suspend fun markRefillNotified(id: String) {
        medicationDao.setRefillNotifiedAt(id, clock.millis())
    }
}
