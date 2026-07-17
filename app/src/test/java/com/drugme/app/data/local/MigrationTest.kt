package com.drugme.app.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migrations must never lose data.
 *
 * This is the failure mode with no recovery: a user's medication list and adherence history
 * exist only on their phone and (encrypted) in their own Firestore. A migration that drops
 * a table destroys a medical record, silently, and the app looks fine afterwards — it just
 * has nothing in it. The alternative most projects reach for,
 * fallbackToDestructiveMigration, does exactly that by design, which is why it is
 * deliberately absent from DatabaseModule.
 *
 * Runs under Robolectric so it executes on the JVM in CI. sdk=34 rather than 36 because
 * Robolectric ships prebuilt Android runtimes and lags the newest API by a release or two.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DrugMeDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate 1 to 2 keeps existing medications`() {
        val dbName = "migration-test-1"

        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO medications
                    (id, name, rxcui, doseAmount, doseUnit, diseaseId, diseaseName,
                     notes, isActive, createdAt, updatedAt)
                VALUES
                    ('m1', 'metformin', '6809', 500.0, 'MG', 'D003924',
                     'Diabetes Mellitus, Type 2', 'with breakfast', 1, 1000, 1000)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2)

        db.query("SELECT * FROM medications WHERE id = 'm1'").use { c ->
            assertTrue("the medication survived the migration", c.moveToFirst())
            assertEquals("metformin", c.getString(c.getColumnIndexOrThrow("name")))
            assertEquals(500.0, c.getDouble(c.getColumnIndexOrThrow("doseAmount")), 0.001)
            assertEquals("with breakfast", c.getString(c.getColumnIndexOrThrow("notes")))
            assertEquals("D003924", c.getString(c.getColumnIndexOrThrow("diseaseId")))
        }
    }

    @Test
    fun `migrate 1 to 2 defaults existing rows to no food preference`() {
        val dbName = "migration-test-2"
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO medications
                    (id, name, doseAmount, doseUnit, isActive, createdAt, updatedAt)
                VALUES ('m1', 'aspirin', 75.0, 'MG', 1, 1000, 1000)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2)

        db.query("SELECT foodRelation FROM medications WHERE id = 'm1'").use { c ->
            c.moveToFirst()
            // Rows that predate the concept must land on "no preference stated", not on an
            // invented instruction.
            assertEquals("ANY", c.getString(0))
        }
    }

    @Test
    fun `migrate 1 to 2 leaves existing rows not tracking stock`() {
        val dbName = "migration-test-3"
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO medications
                    (id, name, doseAmount, doseUnit, isActive, createdAt, updatedAt)
                VALUES ('m1', 'aspirin', 75.0, 'MG', 1, 1000, 1000)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2)

        db.query("SELECT stockAmount, refillReminderDays FROM medications WHERE id = 'm1'").use { c ->
            c.moveToFirst()
            // NULL, not 0. Defaulting to zero would mean "I have run out" and fire a refill
            // warning at every existing medication the moment the user updated the app.
            assertTrue("stock must be NULL for pre-existing rows", c.isNull(0))
            assertEquals(7, c.getInt(1))
        }
    }

    @Test
    fun `migrate 1 to 2 keeps dose history`() {
        val dbName = "migration-test-4"
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO medications (id, name, doseAmount, doseUnit, isActive, createdAt, updatedAt)
                VALUES ('m1', 'aspirin', 75.0, 'MG', 1, 1000, 1000)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO schedules
                    (id, medicationId, type, timesOfDay, weekdays, intervalDays,
                     startDate, endDate, createdAt, updatedAt)
                VALUES ('s1', 'm1', 'TIMES_PER_DAY', '08:00', 127, 1,
                        '2026-01-01', NULL, 1000, 1000)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO doses
                    (id, medicationId, scheduleId, scheduledAt, localDate, status, takenAt, snoozedUntil)
                VALUES ('d1', 'm1', 's1', 5000, '2026-01-01', 'TAKEN', 5100, NULL)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2)

        // Adherence history is the thing a user would most obviously notice losing.
        db.query("SELECT status, takenAt FROM doses WHERE id = 'd1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("TAKEN", c.getString(0))
            assertEquals(5100L, c.getLong(1))
        }
        db.query("SELECT timesOfDay FROM schedules WHERE id = 's1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("08:00", c.getString(0))
        }
    }

    @Test
    fun `migrate 1 to 2 creates a working disease catalog with its FTS index`() {
        val dbName = "migration-test-5"
        helper.createDatabase(dbName, 1).close()

        val db = helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2)

        db.execSQL("INSERT INTO disease_catalog (id, name) VALUES ('D003924', 'Diabetes Mellitus, Type 2')")

        // The FTS triggers are hand-written in the migration (Room only generates them when
        // it creates the schema itself). Without them the table exists, inserts succeed, and
        // search silently returns nothing forever.
        db.query("SELECT name FROM disease_catalog_fts WHERE disease_catalog_fts MATCH '\"diabetes\"*'").use { c ->
            assertTrue("FTS index was not populated — triggers missing", c.moveToFirst())
            assertEquals("Diabetes Mellitus, Type 2", c.getString(0))
        }
    }
}
