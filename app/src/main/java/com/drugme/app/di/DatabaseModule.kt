package com.drugme.app.di

import android.content.Context
import androidx.room.Room
import com.drugme.app.data.local.ALL_MIGRATIONS
import com.drugme.app.data.local.DrugMeDatabase
import com.drugme.app.data.local.dao.DiseaseCatalogDao
import com.drugme.app.data.local.dao.DoseDao
import com.drugme.app.data.local.dao.DrugCatalogDao
import com.drugme.app.data.local.dao.MedicationDao
import com.drugme.app.data.local.dao.ScheduleDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DrugMeDatabase =
        Room.databaseBuilder(context, DrugMeDatabase::class.java, DrugMeDatabase.NAME)
            // No fallbackToDestructiveMigration: this data cannot be re-derived from
            // anywhere. Dropping the database on a schema mismatch would silently delete
            // a user's medication history, so every future version ships a real migration.
            .addMigrations(*ALL_MIGRATIONS)
            .build()

    @Provides fun provideMedicationDao(db: DrugMeDatabase): MedicationDao = db.medicationDao()
    @Provides fun provideScheduleDao(db: DrugMeDatabase): ScheduleDao = db.scheduleDao()
    @Provides fun provideDoseDao(db: DrugMeDatabase): DoseDao = db.doseDao()
    @Provides fun provideDrugCatalogDao(db: DrugMeDatabase): DrugCatalogDao = db.drugCatalogDao()
    @Provides fun provideDiseaseCatalogDao(db: DrugMeDatabase): DiseaseCatalogDao = db.diseaseCatalogDao()
}
