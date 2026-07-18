package com.drugme.app.data.sync

import android.util.Log
import com.drugme.app.data.auth.AuthRepository
import com.drugme.app.data.crypto.Envelope
import com.drugme.app.data.crypto.Sealed
import com.drugme.app.data.crypto.VaultManager
import com.drugme.app.data.local.dao.MedicationDao
import com.drugme.app.data.local.dao.ScheduleDao
import com.drugme.app.data.repo.DoseRepository
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SyncResult {
    data class Success(val pushed: Int, val pulled: Int) : SyncResult
    data object NotSignedIn : SyncResult
    data object Locked : SyncResult
    data class Failure(val message: String) : SyncResult
}

/**
 * Two-way sync of encrypted medication records.
 *
 * Firestore is a dumb blob store here: it holds ciphertext, a nonce, a tombstone flag and
 * a timestamp, and cannot interpret any of it. That's what makes end-to-end encryption
 * affordable in this app — Room already answers every query locally, so the server never
 * needs to index, filter or sort anything. Sync is "download my blobs, decrypt locally".
 *
 * Conflict resolution is last-write-wins on updatedAt. Adequate because this is one user
 * across their own devices, not concurrent editors: the loser of a race is a stale copy of
 * the same person's intent, not someone else's work.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: AuthRepository,
    private val vault: VaultManager,
    private val medicationDao: MedicationDao,
    private val scheduleDao: ScheduleDao,
    private val doseRepository: DoseRepository,
    private val clock: Clock,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun sync(): SyncResult {
        val uid = auth.currentUser?.uid ?: return SyncResult.NotSignedIn
        if (!vault.isUnlocked) return SyncResult.Locked

        return runCatching {
            val pulled = pull(uid)
            val pushed = push(uid)

            // Remote changes alter what is due; regenerate and re-arm so a medication added
            // on another device actually produces reminders on this one.
            if (pulled > 0) {
                doseRepository.materializeWindow()
            }
            SyncResult.Success(pushed = pushed, pulled = pulled)
        }.getOrElse { t ->
            Log.e(TAG, "Sync failed", t)
            SyncResult.Failure(t.message ?: "Sync failed")
        }
    }

    /** Uploads every local medication as an encrypted blob. */
    private suspend fun push(uid: String): Int {
        val locals = medicationDao.observeAllOnce()
        var count = 0

        for (item in locals) {
            val payload = item.medication.toPayload(
                item.schedules,
                doseRepository.actedDosesForBackup(item.medication.id),
            )
            val plaintext = json.encodeToString(payload).toByteArray()

            val sealed = vault.seal(plaintext, item.medication.id, uid) ?: continue

            val doc = mapOf(
                "ciphertext" to Envelope.b64(sealed.ciphertext),
                "nonce" to Envelope.b64(sealed.nonce),
                "updatedAt" to item.medication.updatedAt.toEpochMilli(),
                "deleted" to false,
            )
            records(uid).document(item.medication.id).set(doc, SetOptions.merge()).await()
            count++
        }
        return count
    }

    /** Downloads remote blobs and applies any that are newer than the local copy. */
    private suspend fun pull(uid: String): Int {
        val snapshot = records(uid).get().await()
        var applied = 0

        for (doc in snapshot.documents) {
            val deleted = doc.getBoolean("deleted") ?: false
            val recordId = doc.id

            if (deleted) {
                medicationDao.getById(recordId)?.let {
                    medicationDao.delete(recordId)
                    applied++
                }
                continue
            }

            val ciphertext = doc.getString("ciphertext") ?: continue
            val nonce = doc.getString("nonce") ?: continue
            val remoteUpdated = doc.getLong("updatedAt") ?: 0L

            val local = medicationDao.getById(recordId)
            // Last-write-wins. Equal timestamps are treated as no change so a sync loop
            // can't ping-pong the same record between devices forever.
            if (local != null && local.medication.updatedAt.toEpochMilli() >= remoteUpdated) continue

            val plaintext = vault.open(
                Sealed(Envelope.unb64(nonce), Envelope.unb64(ciphertext)),
                recordId,
                uid,
            )
            if (plaintext == null) {
                // Undecryptable means the blob belongs to a different DEK — e.g. a vault
                // reset. Skip it rather than deleting: the user's other device may still
                // hold the key, and destroying data we merely cannot read would be
                // unforgivable.
                Log.w(TAG, "Could not decrypt record $recordId; skipping")
                continue
            }

            val payload = runCatching {
                json.decodeFromString<MedicationPayload>(plaintext.decodeToString())
            }.getOrElse {
                Log.e(TAG, "Malformed payload in $recordId", it)
                continue
            }

            medicationDao.upsert(payload.toEntity())
            // Replacing schedules cascades away this medication's doses, so restore the
            // backed-up history right after — before materializeWindow (IGNORE) refills the
            // future with pending doses that must not clobber a taken/skipped mark.
            scheduleDao.deleteForMedication(payload.id)
            scheduleDao.upsertAll(payload.schedules.map { it.toEntity(payload.id) })
            doseRepository.restoreDoses(payload.doses.map { it.toEntity(payload.id) })
            applied++
        }
        return applied
    }

    /**
     * Tombstones a record so the deletion propagates.
     *
     * A hard delete would be undone by the next device that pushes its still-present copy.
     */
    suspend fun markDeleted(medicationId: String) {
        val uid = auth.currentUser?.uid ?: return
        runCatching {
            records(uid).document(medicationId).set(
                mapOf(
                    "deleted" to true,
                    "updatedAt" to clock.millis(),
                    // Ciphertext is cleared on delete: retaining it would keep the drug
                    // name on the server after the user asked for it to be gone.
                    "ciphertext" to null,
                    "nonce" to null,
                ),
                SetOptions.merge(),
            ).await()
        }.onFailure { Log.e(TAG, "Could not tombstone $medicationId", it) }
    }

    private fun records(uid: String) =
        firestore.collection("users").document(uid).collection("records")

    private companion object {
        const val TAG = "SyncEngine"
    }
}
