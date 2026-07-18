package com.drugme.app

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug-variant App Check.
 *
 * Uses the debug provider, which prints a token to Logcat on first run. Register that token
 * under Firebase console → App Check → Manage debug tokens so local and CI builds pass App
 * Check without Play Integrity (which only works for Play-signed installs). This file lives in
 * src/debug so the debug provider never reaches a release build; src/release supplies the
 * Play Integrity counterpart under the same name.
 */
internal object AppCheckInstaller {
    fun install() {
        FirebaseAppCheck.getInstance()
            .installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
    }
}
