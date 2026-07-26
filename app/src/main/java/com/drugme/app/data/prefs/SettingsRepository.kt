package com.drugme.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// DataStore, not EncryptedSharedPreferences — the latter was deprecated in
// androidx.security:security-crypto:1.1.0-alpha07. Nothing secret lives here; key
// material goes through the Keystore-backed path in data/crypto instead.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Hides drug names in notifications entirely, including once unlocked. */
    val discreetNotifications: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_DISCREET] ?: false }

    /**
     * Null means the user has not chosen yet, so the UI follows the device theme. Once
     * chosen, the explicit light/dark value is retained across launches.
     */
    val darkMode: Flow<Boolean?> =
        context.dataStore.data.map { it[KEY_DARK_MODE] }

    val onboardingComplete: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_ONBOARDED] ?: false }

    /** True after the user explicitly chooses local-only use; avoids nagging every launch. */
    val backupPromptDismissed: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_BACKUP_PROMPT_DISMISSED] ?: false }

    val disclaimerAccepted: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_DISCLAIMER] ?: false }

    val batteryPromptShown: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_BATTERY_PROMPT] ?: false }

    val catalogLoaded: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_CATALOG_LOADED] ?: false }

    val diseaseCatalogLoaded: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_DISEASE_CATALOG_LOADED] ?: false }

    suspend fun setDiscreetNotifications(value: Boolean) = put(KEY_DISCREET, value)
    suspend fun setDarkMode(value: Boolean) = put(KEY_DARK_MODE, value)
    suspend fun setOnboardingComplete(value: Boolean) = put(KEY_ONBOARDED, value)
    suspend fun setBackupPromptDismissed(value: Boolean) = put(KEY_BACKUP_PROMPT_DISMISSED, value)
    suspend fun setDisclaimerAccepted(value: Boolean) = put(KEY_DISCLAIMER, value)
    suspend fun setBatteryPromptShown(value: Boolean) = put(KEY_BATTERY_PROMPT, value)
    suspend fun setCatalogLoaded(value: Boolean) = put(KEY_CATALOG_LOADED, value)
    suspend fun setDiseaseCatalogLoaded(value: Boolean) = put(KEY_DISEASE_CATALOG_LOADED, value)

    private suspend fun put(key: Preferences.Key<Boolean>, value: Boolean) {
        context.dataStore.edit { it[key] = value }
    }

    private companion object {
        val KEY_DISCREET = booleanPreferencesKey("discreet_notifications")
        val KEY_DARK_MODE = booleanPreferencesKey("dark_mode")
        val KEY_ONBOARDED = booleanPreferencesKey("onboarding_complete")
        val KEY_BACKUP_PROMPT_DISMISSED = booleanPreferencesKey("backup_prompt_dismissed")
        val KEY_DISCLAIMER = booleanPreferencesKey("disclaimer_accepted")
        val KEY_BATTERY_PROMPT = booleanPreferencesKey("battery_prompt_shown")
        val KEY_CATALOG_LOADED = booleanPreferencesKey("catalog_loaded")
        val KEY_DISEASE_CATALOG_LOADED = booleanPreferencesKey("disease_catalog_loaded")
    }
}
