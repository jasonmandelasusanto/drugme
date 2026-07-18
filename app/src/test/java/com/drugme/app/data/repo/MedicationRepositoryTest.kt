package com.drugme.app.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.drugme.app.alarm.DoseAlarmScheduler
import com.drugme.app.data.local.DrugMeDatabase
import com.drugme.app.data.local.entity.MedicationEntity
import com.drugme.app.data.local.entity.ScheduleEntity
import com.drugme.app.data.sync.SyncTrigger
import com.drugme.app.domain.model.DoseUnit
import com.drugme.app.domain.model.ScheduleType
import com.drugme.app.domain.schedule.StockForecast
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Guards the invariant whose absence lost user data: **every medication mutation must request
 * a backup.**
 *
 * Before the fix, saving a medication wrote it only to the local database and never asked for
 * an upload — so it existed nowhere else, and uninstalling the app destroyed it with no way
 * back. These tests fail if any mutation path stops requesting a sync, which is exactly the
 * regression that shipped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MedicationRepositoryTest {

    private lateinit var db: DrugMeDatabase
    private val clock = Clock.fixed(Instant.parse("2026-03-02T08:00:00Z"), ZoneOffset.UTC)

    /** Records how many times a backup was requested. */
    private class RecordingSyncTrigger : SyncTrigger {
        var syncs = 0
        override fun requestSync() { syncs++ }
    }

    private val syncTrigger = RecordingSyncTrigger()
    private lateinit var repository: MedicationRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DrugMeDatabase::class.java,
        ).allowMainThreadQueries().build()

        repository = MedicationRepository(
            db = db,
            medicationDao = db.medicationDao(),
            scheduleDao = db.scheduleDao(),
            doseRepository = mockk(relaxed = true),
            alarmScheduler = mockk<DoseAlarmScheduler>(relaxed = true),
            stockForecast = mockk<StockForecast>(relaxed = true),
            clock = clock,
            syncTrigger = syncTrigger,
        )
    }

    @After
    fun tearDown() = db.close()

    private fun medication(id: String) = MedicationEntity(
        id = id,
        name = id,
        doseAmount = 1.0,
        doseUnit = DoseUnit.TABLET,
        createdAt = clock.instant(),
        updatedAt = clock.instant(),
    )

    private fun schedule(id: String, medicationId: String) = ScheduleEntity(
        id = id,
        medicationId = medicationId,
        type = ScheduleType.TIMES_PER_DAY,
        timesOfDay = listOf(LocalTime.of(8, 0)),
        startDate = LocalDate.parse("2026-03-02"),
        createdAt = clock.instant(),
        updatedAt = clock.instant(),
    )

    @Test
    fun `saving a medication requests a backup`() = runTest {
        repository.save(medication("m1"), listOf(schedule("s1", "m1")))

        // The whole point: a newly added medication must not live only on the phone.
        assertTrue("save() must request a backup", syncTrigger.syncs >= 1)
    }

    @Test
    fun `deleting a medication requests a backup`() = runTest {
        repository.save(medication("m1"), listOf(schedule("s1", "m1")))
        syncTrigger.syncs = 0

        repository.delete("m1")

        assertEquals(1, syncTrigger.syncs)
    }

    @Test
    fun `pausing a medication requests a backup`() = runTest {
        repository.save(medication("m1"), listOf(schedule("s1", "m1")))
        syncTrigger.syncs = 0

        repository.setActive("m1", active = false)

        assertEquals(1, syncTrigger.syncs)
    }

    @Test
    fun `changing stock requests a backup`() = runTest {
        repository.save(medication("m1"), listOf(schedule("s1", "m1")))
        syncTrigger.syncs = 0

        repository.setStock("m1", 30.0)

        assertEquals(1, syncTrigger.syncs)
    }
}
