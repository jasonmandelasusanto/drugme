package com.drugme.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.drugme.app.data.local.dao.MedicationDao
import com.drugme.app.data.local.entity.MedicationEntity
import com.drugme.app.domain.model.DoseUnit
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Stock arithmetic against a real (in-memory) SQLite database.
 *
 * The clamping and the null-guard are expressed in SQL, not Kotlin, so they can only be
 * verified by actually running the query — a Kotlin-level mock would happily agree with a
 * broken statement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StockDaoTest {

    private lateinit var db: DrugMeDatabase
    private lateinit var dao: MedicationDao

    private val now = Instant.parse("2026-03-02T00:00:00Z")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DrugMeDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.medicationDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insert(stock: Double?, dose: Double = 1.0): String {
        val id = "m1"
        dao.upsert(
            MedicationEntity(
                id = id,
                name = "test",
                doseAmount = dose,
                doseUnit = DoseUnit.TABLET,
                stockAmount = stock,
                createdAt = now,
                updatedAt = now,
            )
        )
        return id
    }

    @Test
    fun `taking a dose reduces stock`() = runTest {
        val id = insert(stock = 10.0)
        dao.adjustStock(id, -1.0, now.toEpochMilli())
        assertEquals(9.0, dao.getMedication(id)!!.stockAmount!!, 0.001)
    }

    @Test
    fun `undoing a dose gives the stock back`() = runTest {
        val id = insert(stock = 10.0)
        dao.adjustStock(id, -1.0, now.toEpochMilli())
        dao.adjustStock(id, +1.0, now.toEpochMilli())

        // Symmetry is what stops a mis-tap silently costing stock. Without it, toggling
        // taken/untaken a few times drains a month's supply on paper.
        assertEquals(10.0, dao.getMedication(id)!!.stockAmount!!, 0.001)
    }

    @Test
    fun `stock never goes negative`() = runTest {
        val id = insert(stock = 1.0)
        dao.adjustStock(id, -5.0, now.toEpochMilli())

        // People mark more doses taken than their recorded stock covers — they miscounted,
        // or took some before they started tracking. "-4 tablets left" is meaningless.
        assertEquals(0.0, dao.getMedication(id)!!.stockAmount!!, 0.001)
    }

    @Test
    fun `adjusting an untracked medication does not start tracking it`() = runTest {
        val id = insert(stock = null)
        dao.adjustStock(id, -1.0, now.toEpochMilli())

        // The SQL guards on `stockAmount IS NOT NULL`. Without it, taking a dose would flip
        // an opted-out medication into tracking at a bogus value and start warning about
        // refills the user never asked for.
        assertNull(dao.getMedication(id)!!.stockAmount)
    }

    @Test
    fun `fractional doses subtract exactly`() = runTest {
        val id = insert(stock = 2.5, dose = 0.5)
        repeat(3) { dao.adjustStock(id, -0.5, now.toEpochMilli()) }
        assertEquals(1.0, dao.getMedication(id)!!.stockAmount!!, 0.001)
    }

    @Test
    fun `refill notified flag clears`() = runTest {
        val id = insert(stock = 5.0)
        dao.setRefillNotifiedAt(id, 12345L)
        assertEquals(12345L, dao.getMedication(id)!!.refillNotifiedAt!!.toEpochMilli())

        dao.clearRefillNotified(id)
        // Cleared when stock goes back up, so a future low-stock episode can warn again
        // rather than staying silent forever after one warning.
        assertNull(dao.getMedication(id)!!.refillNotifiedAt)
    }
}
