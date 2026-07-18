package com.drugme.app.data.sync

/**
 * Requests that local data be backed up to the cloud as soon as possible.
 *
 * A seam so the data layer can say "something changed, upload it" without depending on
 * WorkManager — and, just as importantly, so a test can assert that every medication change
 * asks for a backup. That invariant was missing, and its absence is what lost user data:
 * medications were only ever uploaded when the passphrase was typed to unlock, which stops
 * happening once the key is cached.
 */
interface SyncTrigger {
    fun requestSync()
}
