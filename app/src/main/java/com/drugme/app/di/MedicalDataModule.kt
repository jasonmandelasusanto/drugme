package com.drugme.app.di

import com.drugme.app.data.medical.AndroidNetworkStatus
import com.drugme.app.data.medical.DiseaseAutocompleteRepository
import com.drugme.app.data.medical.DiseaseAutocompleteSource
import com.drugme.app.data.medical.MedicationAutocompleteRepository
import com.drugme.app.data.medical.MedicationAutocompleteSource
import com.drugme.app.data.medical.NetworkStatus
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MedicalDataModule {
    @Binds
    @Singleton
    abstract fun bindNetworkStatus(implementation: AndroidNetworkStatus): NetworkStatus

    @Binds
    @Singleton
    abstract fun bindMedicationAutocomplete(
        implementation: MedicationAutocompleteRepository,
    ): MedicationAutocompleteSource

    @Binds
    @Singleton
    abstract fun bindDiseaseAutocomplete(
        implementation: DiseaseAutocompleteRepository,
    ): DiseaseAutocompleteSource
}
