package com.drugme.app.alarm

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The minimum reminder state needed before the first unlock after a reboot.
 *
 * Room deliberately remains in credential-encrypted storage. Copying medication records to
 * device-protected storage would make names, doses and history available before the user's
 * credential is entered. This store carries only distinct epoch-millisecond timestamps, so a
 * direct-boot receiver can say that *a* medication is due without knowing which medication.
 */
@Singleton
class DirectBootReminderStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val storageContext = context.createDeviceProtectedStorageContext()
    private val preferences = storageContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * Replaces the complete rolling shadow queue.
     *
     * commit() is intentional: scheduling an alarm and then losing an asynchronous preference
     * write during a sudden reboot would leave the pre-unlock receiver with no queue.
     */
    @Synchronized
    fun replace(timestamps: List<Long>) {
        write(timestamps.asSequence().filter { it > 0L }.distinct().sorted().toList())
    }

    /** Removes stale entries and returns the earliest remaining reminder. */
    @Synchronized
    fun nextAtOrAfter(cutoffMillis: Long): Long? {
        val current = read()
        val retained = current.dropWhile { it < cutoffMillis }
        if (retained.size != current.size) write(retained)
        return retained.firstOrNull()
    }

    /**
     * Consumes every reminder whose time has arrived.
     *
     * Several medications may share a timestamp, and an alarm delayed by the OS may cross more
     * than one timestamp. One generic notification is enough; the credential-encrypted database
     * remains the source of truth after unlock.
     */
    @Synchronized
    fun consumeDue(nowMillis: Long): Boolean {
        val current = read()
        val firstFuture = current.indexOfFirst { it > nowMillis }
        val dueCount = if (firstFuture == -1) current.size else firstFuture
        if (dueCount == 0) return false
        write(if (firstFuture == -1) emptyList() else current.drop(firstFuture))
        return true
    }

    @Synchronized
    fun clear() {
        write(emptyList())
    }

    /** Visible to focused tests; production callers should use the queue operations above. */
    @Synchronized
    internal fun snapshot(): List<Long> = read()

    private fun read(): List<Long> =
        preferences.getString(KEY_TIMESTAMPS, null)
            ?.split(',')
            ?.mapNotNull(String::toLongOrNull)
            ?.filter { it > 0L }
            ?.distinct()
            ?.sorted()
            .orEmpty()

    private fun write(values: List<Long>) {
        val encoded = values.joinToString(",")
        runCatching {
            preferences.edit(commit = true) { putString(KEY_TIMESTAMPS, encoded) }
        }.onFailure { Log.e(TAG, "Could not persist the direct-boot reminder queue", it) }
    }

    private companion object {
        const val TAG = "DirectBootStore"
        const val PREFERENCES_NAME = "direct_boot_reminders"
        const val KEY_TIMESTAMPS = "timestamps_v1"
    }
}
