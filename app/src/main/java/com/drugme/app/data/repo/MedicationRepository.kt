package com.drugme.app.data.repo

import androidx.room.withTransaction
import com.drugme.app.data.local.DrugMeDatabase
import com.drugme.app.data.local.dao.MedicationDao
import com.drugme.app.data.local.dao.MedicationWithSchedules
import com.drugme.app.data.local.dao.ScheduleDao
import com.drugme.app.data.local.entity.MedicationEntity
import com.drugme.app.data.local.entity.ScheduleEntity
import com.drugme.app.alarm.DoseAlarmScheduler
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MedicationRepository @Inject constructor(
    private val db: DrugMeDatabase,
    private val medicationDao: MedicationDao,
    private val scheduleDao: ScheduleDao,
    private val doseRepository: DoseRepository,
    private val alarmScheduler: DoseAlarmScheduler,
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
}
