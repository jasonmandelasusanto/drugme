package com.drugme.app.di

import com.drugme.app.data.sync.SyncTrigger
import com.drugme.app.data.sync.WorkManagerSyncTrigger
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {

    @Binds
    @Singleton
    abstract fun bindSyncTrigger(impl: WorkManagerSyncTrigger): SyncTrigger
}
