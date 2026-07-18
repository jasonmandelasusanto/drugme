package com.drugme.app

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * Release-variant App Check.
 *
 * Uses Play Integrity, which attests that the app is a genuine, Play-signed install. The
 * debug counterpart lives in src/debug under the same name, so the debug provider is never
 * compiled into a release build.
 */
internal object AppCheckInstaller {
    fun install() {
        FirebaseAppCheck.getInstance()
            .installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
    }
}
