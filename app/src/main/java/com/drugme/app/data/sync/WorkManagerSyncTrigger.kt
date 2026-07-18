package com.drugme.app.data.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The real [SyncTrigger]: enqueues a background [SyncWorker] to push local data. */
@Singleton
class WorkManagerSyncTrigger @Inject constructor(
    @ApplicationContext private val context: Context,
) : SyncTrigger {
    override fun requestSync() = SyncWorker.enqueueNow(context)
}
