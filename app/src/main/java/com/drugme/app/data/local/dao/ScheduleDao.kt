package com.drugme.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.drugme.app.data.local.entity.ScheduleEntity

@Dao
interface ScheduleDao {

    @Query("SELECT * FROM schedules WHERE medicationId = :medicationId")
    suspend fun getForMedication(medicationId: String): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun getById(id: String): ScheduleEntity?

    /**
     * Every schedule that could still produce a dose on or after [today] — i.e. open-ended
     * ones plus those whose "until" date has not passed. Drives the generation window.
     */
    @Query(
        """
        SELECT s.* FROM schedules s
        INNER JOIN medications m ON m.id = s.medicationId
        WHERE m.isActive = 1 AND (s.endDate IS NULL OR s.endDate >= :today)
        """
    )
    suspend fun getGeneratable(today: String): List<ScheduleEntity>

    @Upsert
    suspend fun upsert(schedule: ScheduleEntity)

    @Upsert
    suspend fun upsertAll(schedules: List<ScheduleEntity>)

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM schedules WHERE medicationId = :medicationId")
    suspend fun deleteForMedication(medicationId: String)
}
