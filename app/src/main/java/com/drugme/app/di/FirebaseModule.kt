package com.drugme.app.di

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {

    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()

    @Provides
    @Singleton
    fun provideFirestore(): FirebaseFirestore = FirebaseFirestore.getInstance().apply {
        firestoreSettings = FirebaseFirestoreSettings.Builder()
            // Firestore's on-disk cache is deliberately disabled. It would write a second,
            // unmanaged copy of every synced record to local storage outside Room — and
            // while those records are ciphertext, their document ids, counts and
            // timestamps are not. Room is already the offline source of truth, so the
            // cache buys nothing and only widens the footprint.
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
            .build()
    }
}
