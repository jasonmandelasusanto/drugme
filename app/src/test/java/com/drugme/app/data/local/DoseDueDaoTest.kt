package com.drugme.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.drugme.app.data.local.dao.DoseDao
import com.drugme.app.data.local.dao.MedicationDao
import com.drugme.app.data.local.dao.ScheduleDao
import com.drugme.app.data.local.entity.DoseEntity
import com.drugme.app.data.local.entity.MedicationEntity
import com.drugme.app.data.local.entity.ScheduleEntity
import com.drugme.app.domain.model.DoseStatus
import com.drugme.app.domain.model.DoseUnit
import com.drugme.app.domain.model.ScheduleType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Covers the "several medications due at the same time" reminder path against a real
 * (in-memory) SQLite database.
 *
 * The alarm chain arms one dose, but the receiver notifies whatever
 * [DoseDao.getDuePendingWithMedication] returns — so the number of rows this query yields is
 * exactly the number of reminders posted. The regression it guards: before the fix only the
 * single armed dose was notified, and simultaneous doses were silently dropped. That can only
 * be verified by running the SQL (COALESCE, the `<=` bound and the PENDING filter live in the
 * query, not in Kotlin).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DoseDueDaoTest {

    private lateinit var db: DrugMeDatabase
    private lateinit var medicationDao: MedicationDao
    private lateinit var scheduleDao: ScheduleDao
    private lateinit var doseDao: DoseDao

    private val now = Instant.parse("2026-03-02T08:00:00Z")
    private val today = LocalDate.parse("2026-03-02")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DrugMeDatabase::class.java,
        ).allowMainThreadQueries().build()
        medicationDao = db.medicationDao()
        scheduleDao = db.scheduleDao()
        doseDao = db.doseDao()
    }

    @After
    fun tearDown() = db.close()

    /** Inserts a medication, its schedule, and one dose — the FK parents the dose requires. */
    private suspend fun addDose(
        key: String,
        scheduledAt: Instant,
        status: DoseStatus = DoseStatus.PENDING,
        snoozedUntil: Instant? = null,
    ) {
        medicationDao.upsert(
            MedicationEntity(
                id = "med_$key",
                name = key,
                doseAmount = 1.0,
                doseUnit = DoseUnit.TABLET,
                createdAt = now,
                updatedAt = now,
            )
        )
        scheduleDao.upsert(
            ScheduleEntity(
                id = "sch_$key",
                medicationId = "med_$key",
                type = ScheduleType.TIMES_PER_DAY,
                timesOfDay = listOf(LocalTime.of(8, 0)),
                startDate = today,
                createdAt = now,
                updatedAt = now,
            )
        )
        doseDao.insertIgnore(
            listOf(
                DoseEntity(
                    id = "dose_$key",
                    medicationId = "med_$key",
                    scheduleId = "sch_$key",
                    scheduledAt = scheduledAt,
                    localDate = today,
                    status = status,
                    snoozedUntil = snoozedUntil,
                )
            )
        )
    }

    @Test
    fun `every dose due at the same time is returned`() = runTest {
        // Three different medications, all due at the same instant — the reported bug.
        addDose("a", now)
        addDose("b", now)
        addDose("c", now)

        val due = doseDao.getDuePendingWithMedication(now.toEpochMilli())

        // One row per due dose means one notification per drug.
        assertEquals(3, due.size)
        assertEquals(setOf("a", "b", "c"), due.map { it.medication.name }.toSet())
    }

    @Test
    fun `doses in the future are not yet due`() = runTest {
        addDose("now", now)
        addDose("later", now.plusSeconds(3600))

        val due = doseDao.getDuePendingWithMedication(now.toEpochMilli())

        assertEquals(listOf("now"), due.map { it.medication.name })
    }

    @Test
    fun `doses already acted on are excluded`() = runTest {
        addDose("pending", now, status = DoseStatus.PENDING)
        addDose("taken", now, status = DoseStatus.TAKEN)

        // Filtering on PENDING is what stops a dose the user handled between arming and
        // firing from being re-notified.
        val due = doseDao.getDuePendingWithMedication(now.toEpochMilli())

        assertEquals(listOf("pending"), due.map { it.medication.name })
    }

    @Test
    fun `a snooze moves a dose to its snooze time, not its original slot`() = runTest {
        // Scheduled in the past, snoozed into the future: not due until the snooze target.
        addDose("snoozed", now.minusSeconds(3600), snoozedUntil = now.plusSeconds(600))
        addDose("due", now)

        val due = doseDao.getDuePendingWithMedication(now.toEpochMilli())

        assertEquals(listOf("due"), due.map { it.medication.name })
    }
}
