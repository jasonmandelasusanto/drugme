package com.drugme.app.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs the real migration SQL against Android SQLite in memory.
 *
 * Room 2.8's MigrationTestHelper currently compares a Windows Robolectric absolute path
 * with the configured relative database name and rejects every database before a migration
 * runs. Using the same FrameworkSQLiteDatabase directly keeps these tests cross-platform
 * while still exercising SQLite's actual ALTER TABLE, FTS and trigger behaviour.
 * Room's exported schema is independently generated and checked by KSP on every build.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    @Test
    fun `migrate 1 to 2 keeps medication and applies safe defaults`() = withV1 { db ->
        db.execSQL(
            """
            INSERT INTO medications
                (id, name, rxcui, doseAmount, doseUnit, diseaseId, diseaseName,
                 notes, isActive, createdAt, updatedAt)
            VALUES ('m1', 'metformin', '6809', 500.0, 'MG', 'D003924',
                    'Diabetes Mellitus, Type 2', 'with breakfast', 1, 1000, 1000)
            """.trimIndent()
        )

        MIGRATION_1_2.migrate(db)

        db.query(
            "SELECT name, doseAmount, notes, diseaseId, foodRelation, stockAmount, refillReminderDays " +
                "FROM medications WHERE id = 'm1'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("metformin", c.getString(0))
            assertEquals(500.0, c.getDouble(1), 0.001)
            assertEquals("with breakfast", c.getString(2))
            assertEquals("D003924", c.getString(3))
            assertEquals("ANY", c.getString(4))
            assertTrue(c.isNull(5))
            assertEquals(7, c.getInt(6))
        }
    }

    @Test
    fun `migrate 1 to 2 keeps schedule and dose history`() = withV1 { db ->
        insertMedicationScheduleAndDose(db)
        MIGRATION_1_2.migrate(db)

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
    fun `migrate 1 to 2 creates a working disease FTS index`() = withV1 { db ->
        MIGRATION_1_2.migrate(db)
        db.execSQL("INSERT INTO disease_catalog (id, name) VALUES ('D003924', 'Diabetes Mellitus, Type 2')")

        db.query("SELECT name FROM disease_catalog_fts WHERE disease_catalog_fts MATCH '\"diabetes\"*'").use { c ->
            assertTrue("FTS index was not populated — triggers missing", c.moveToFirst())
            assertEquals("Diabetes Mellitus, Type 2", c.getString(0))
        }
    }

    @Test
    fun `migrate 2 to 3 preserves history and adds nullable snapshots`() = withV1 { db ->
        MIGRATION_1_2.migrate(db)
        insertMedicationScheduleAndDose(db)
        MIGRATION_2_3.migrate(db)

        db.query("SELECT status, doseAmount, doseUnit, note FROM doses WHERE id = 'd1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("TAKEN", c.getString(0))
            assertTrue(c.isNull(1))
            assertTrue(c.isNull(2))
            assertTrue(c.isNull(3))
        }
        db.query("SELECT stockUnit, stockPerDose FROM medications WHERE id = 'm1'").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
            assertTrue(c.isNull(1))
        }
    }

    private fun withV1(block: (SupportSQLiteDatabase) -> Unit) {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(
                ApplicationProvider.getApplicationContext()
            )
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        val database = helper.writableDatabase
        try {
            database.execSQL(
                """
                CREATE TABLE medications (
                    id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, rxcui TEXT,
                    doseAmount REAL NOT NULL, doseUnit TEXT NOT NULL, diseaseId TEXT,
                    diseaseName TEXT, notes TEXT, isActive INTEGER NOT NULL DEFAULT 1,
                    createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE TABLE schedules (
                    id TEXT NOT NULL PRIMARY KEY, medicationId TEXT NOT NULL, type TEXT NOT NULL,
                    timesOfDay TEXT NOT NULL, weekdays INTEGER NOT NULL, intervalDays INTEGER NOT NULL,
                    startDate TEXT NOT NULL, endDate TEXT, createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL, FOREIGN KEY(medicationId) REFERENCES medications(id)
                    ON DELETE CASCADE
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE TABLE doses (
                    id TEXT NOT NULL PRIMARY KEY, medicationId TEXT NOT NULL, scheduleId TEXT NOT NULL,
                    scheduledAt INTEGER NOT NULL, localDate TEXT NOT NULL, status TEXT NOT NULL,
                    takenAt INTEGER, snoozedUntil INTEGER,
                    FOREIGN KEY(medicationId) REFERENCES medications(id) ON DELETE CASCADE,
                    FOREIGN KEY(scheduleId) REFERENCES schedules(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            block(database)
        } finally {
            helper.close()
        }
    }

    private fun insertMedicationScheduleAndDose(db: SupportSQLiteDatabase) {
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
}
