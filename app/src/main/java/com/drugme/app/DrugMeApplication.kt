package com.drugme.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class DrugMeApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        // App Check hardens the backend so only the genuine app can call Firestore/Auth. The
        // provider differs per variant (Play Integrity in release, debug token in debug) — see
        // AppCheckInstaller. Guarded because Robolectric/unit runs have no configured
        // FirebaseApp, and a missing-Firebase crash here must not take the whole app down.
        runCatching { AppCheckInstaller.install() }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
