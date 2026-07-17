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

    @Query("SELECT * FROM medications WHERE id = :id")
    suspend fun getMedication(id: String): MedicationEntity?

    /**
     * Adjusts stock by [delta] (negative to consume, positive to give back).
     *
     * Clamped at zero: a user can mark more doses taken than their recorded stock covers —
     * they miscounted, or took some before they started tracking — and a negative stock
     * would render as "-3 tablets left", which is meaningless.
     *
     * The `stockAmount IS NOT NULL` guard keeps this a no-op for medications where the user
     * never opted into tracking. Without it, taking a dose would silently switch tracking
     * on at a bogus value.
     */
    @Query(
        """
        UPDATE medications
        SET stockAmount = MAX(0, stockAmount + :delta), updatedAt = :now
        WHERE id = :id AND stockAmount IS NOT NULL
        """
    )
    suspend fun adjustStock(id: String, delta: Double, now: Long)

    @Query("UPDATE medications SET refillNotifiedAt = :at WHERE id = :id")
    suspend fun setRefillNotifiedAt(id: String, at: Long?)

    /** Clears the warning flag so a future low-stock event can notify again. */
    @Query("UPDATE medications SET refillNotifiedAt = NULL WHERE id = :id")
    suspend fun clearRefillNotified(id: String)

    @Query("DELETE FROM medications WHERE id = :id")
    suspend fun delete(id: String)

    /** Wipes every medication. Cascades to schedules and doses. Used only by account deletion. */
    @Query("DELETE FROM medications")
    suspend fun deleteAll()
}
