package com.drugme.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.drugme.app.data.local.dao.DiseaseCatalogDao
import com.drugme.app.data.local.dao.DoseDao
import com.drugme.app.data.local.dao.DrugCatalogDao
import com.drugme.app.data.local.dao.MedicationDao
import com.drugme.app.data.local.dao.ScheduleDao
import com.drugme.app.data.local.entity.DiseaseCatalogEntity
import com.drugme.app.data.local.entity.DiseaseCatalogFts
import com.drugme.app.data.local.entity.DoseEntity
import com.drugme.app.data.local.entity.DrugCatalogEntity
import com.drugme.app.data.local.entity.DrugCatalogFts
import com.drugme.app.data.local.entity.MedicationEntity
import com.drugme.app.data.local.entity.ScheduleEntity

/**
 * Stores plaintext, by design.
 *
 * The device sits inside the trust boundary: Android's app sandbox plus full-disk
 * encryption already protect data at rest here, and the threat model this app defends
 * against is the *server* reading medication data. Encryption happens at the sync
 * boundary on the way out (see data/crypto + data/sync). Encrypting the database itself
 * would break FTS type-ahead and every indexed query while buying nothing against the
 * attacker we actually care about.
 *
 * Android auto-backup is disabled in the manifest to keep this plaintext file from being
 * copied to Google Drive, which would route around that boundary entirely.
 */
@Database(
    entities = [
        MedicationEntity::class,
        ScheduleEntity::class,
        DoseEntity::class,
        DrugCatalogEntity::class,
        DrugCatalogFts::class,
        DiseaseCatalogEntity::class,
        DiseaseCatalogFts::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class DrugMeDatabase : RoomDatabase() {
    abstract fun medicationDao(): MedicationDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun doseDao(): DoseDao
    abstract fun drugCatalogDao(): DrugCatalogDao
    abstract fun diseaseCatalogDao(): DiseaseCatalogDao

    companion object {
        const val NAME = "drugme.db"
    }
}
