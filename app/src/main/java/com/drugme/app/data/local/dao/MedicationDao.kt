package com.drugme.app.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.drugme.app.data.local.entity.MedicationEntity
import com.drugme.app.data.local.entity.ScheduleEntity
import kotlinx.coroutines.flow.Flow

/** A medication together with its schedules, which are always loaded as a unit. */
data class MedicationWithSchedules(
    @Embedded val medication: MedicationEntity,
    @Relation(parentColumn = "id", entityColumn = "medicationId")
    val schedules: List<ScheduleEntity>,
)

@Dao
interface MedicationDao {

    @Transaction
    @Query("SELECT * FROM medications WHERE isActive = 1 ORDER BY name COLLATE NOCASE ASC")
    fun observeActive(): Flow<List<MedicationWithSchedules>>

    @Transaction
    @Query("SELECT * FROM medications ORDER BY isActive DESC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<MedicationWithSchedules>>

    @Transaction
    @Query("SELECT * FROM medications WHERE id = :id")
    fun observeById(id: String): Flow<MedicationWithSchedules?>

    @Transaction
    @Query("SELECT * FROM medications WHERE id = :id")
    suspend fun getById(id: String): MedicationWithSchedules?

    @Transaction
    @Query("SELECT * FROM medications WHERE isActive = 1")
    suspend fun getActive(): List<MedicationWithSchedules>

    /**
     * One-shot read of every medication, including inactive ones.
     *
     * Used by the sync push: a paused medication is still the user's data and must reach
     * their other devices, so this deliberately does not filter on isActive.
     */
    @Transaction
    @Query("SELECT * FROM medications")
    suspend fun observeAllOnce(): List<MedicationWithSchedules>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(medication: MedicationEntity)

    @Update
    suspend fun update(medication: MedicationEntity)

    @Upsert
    suspend fun upsert(medication: MedicationEntity)

    @Query("UPDATE medications SET isActive = :active, updatedAt = :now WHERE id = :id")
    suspend fun setActive(id: String, active: Boolean, now: Long)

    @Query("DELETE FROM medications WHERE id = :id")
    suspend fun delete(id: String)
}
