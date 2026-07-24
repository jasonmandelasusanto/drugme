package com.drugme.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations.
 *
 * Every version ships a real migration. There is deliberately no
 * fallbackToDestructiveMigration anywhere: this data cannot be re-derived from anything —
 * a user's medication list and their adherence history exist only here and (encrypted) in
 * their own Firestore. Dropping the database on a schema mismatch would silently delete
 * someone's medical record to save us writing an ALTER TABLE.
 */

/**
 * v1 -> v2
 *  - medications.foodRelation: with/before/after food, empty stomach, or no preference.
 *  - medications stock tracking: how much is left, when to warn, when last warned.
 *  - disease_catalog (+ FTS): conditions are now the user's own statement, chosen from the
 *    full MeSH list, rather than being derived from the drug's RxNorm indications.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Existing rows predate the concept, so they get ANY — "no preference stated".
        // NOT NULL with a default, so the column can be added without rewriting the table.
        db.execSQL(
            "ALTER TABLE medications ADD COLUMN foodRelation TEXT NOT NULL DEFAULT 'ANY'"
        )

        // Nullable: null means "not tracking stock", which is different from 0 ("run out").
        // Existing rows must land on not-tracking, not on empty.
        db.execSQL("ALTER TABLE medications ADD COLUMN stockAmount REAL DEFAULT NULL")
        db.execSQL("ALTER TABLE medications ADD COLUMN refillReminderDays INTEGER NOT NULL DEFAULT 7")
        db.execSQL("ALTER TABLE medications ADD COLUMN refillNotifiedAt INTEGER DEFAULT NULL")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `disease_catalog` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )

        // External-content FTS: tokens are indexed here, rows live in disease_catalog, so
        // ~6k names are not stored twice.
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS `disease_catalog_fts`
            USING FTS4(`name` TEXT NOT NULL, content=`disease_catalog`)
            """.trimIndent()
        )

        // Room generates these triggers for @Fts4(contentEntity = ...). Hand-written here
        // because a migration runs before Room's own schema creation, and without them the
        // index would silently never update.
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_disease_catalog_fts_BEFORE_UPDATE
            BEFORE UPDATE ON `disease_catalog` BEGIN
                DELETE FROM `disease_catalog_fts` WHERE `docid` = OLD.`rowid`;
            END
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_disease_catalog_fts_BEFORE_DELETE
            BEFORE DELETE ON `disease_catalog` BEGIN
                DELETE FROM `disease_catalog_fts` WHERE `docid` = OLD.`rowid`;
            END
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_disease_catalog_fts_AFTER_UPDATE
            AFTER UPDATE ON `disease_catalog` BEGIN
                INSERT INTO `disease_catalog_fts`(`docid`, `name`) VALUES (NEW.`rowid`, NEW.`name`);
            END
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_disease_catalog_fts_AFTER_INSERT
            AFTER INSERT ON `disease_catalog` BEGIN
                INSERT INTO `disease_catalog_fts`(`docid`, `name`) VALUES (NEW.`rowid`, NEW.`name`);
            END
            """.trimIndent()
        )
    }
}

/**
 * v2 -> v3
 *  - dose snapshots keep historical amounts truthful after prescription edits.
 *  - schedule amount overrides support different morning/evening doses.
 *  - stock unit/consumption decouple "500 mg taken" from "one tablet used".
 *  - per-dose notes add context to History.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE medications ADD COLUMN stockUnit TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE medications ADD COLUMN stockPerDose REAL DEFAULT NULL")
        db.execSQL("ALTER TABLE schedules ADD COLUMN doseAmount REAL DEFAULT NULL")
        db.execSQL("ALTER TABLE schedules ADD COLUMN doseUnit TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE doses ADD COLUMN doseAmount REAL DEFAULT NULL")
        db.execSQL("ALTER TABLE doses ADD COLUMN doseUnit TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE doses ADD COLUMN note TEXT DEFAULT NULL")
    }
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
