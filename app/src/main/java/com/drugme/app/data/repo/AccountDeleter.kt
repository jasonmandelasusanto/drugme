package com.drugme.app.data.repo

import android.util.Log
import androidx.room.withTransaction
import com.drugme.app.alarm.DoseAlarmScheduler
import com.drugme.app.data.auth.AuthRepository
import com.drugme.app.data.crypto.VaultManager
import com.drugme.app.data.local.DrugMeDatabase
import com.drugme.app.data.local.dao.MedicationDao
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deletes an account and everything belonging to it.
 *
 * Order matters, and it is the reverse of what feels natural. The remote data is deleted
 * *before* the Firebase user, because the Firestore security rules authorise on
 * `request.auth.uid` — delete the user first and the rules immediately deny access to that
 * user's own documents, orphaning them on the server forever with no way to reach them
 * again. "Delete my account" would then leave the ciphertext behind indefinitely.
 *
 * Local data goes last: if anything upstream fails, the user still has their medications
 * and can retry, rather than losing the local copy to a half-finished delete.
 */
@Singleton
class AccountDeleter @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val auth: AuthRepository,
    private val vault: VaultManager,
    private val db: DrugMeDatabase,
    private val medicationDao: MedicationDao,
    private val alarmScheduler: DoseAlarmScheduler,
) {

    /**
     * @return failure if the remote delete could not be completed — local data is left
     *   intact in that case so the user can try again.
     */
    suspend fun deleteEverything(): Result<Unit> = runCatching {
        val user = firebaseAuth.currentUser
        val uid = user?.uid

        if (uid != null) {
            deleteRemote(uid)

            // Only now is it safe to remove the user. This can fail with
            // FirebaseAuthRecentLoginRequiredException if the session is old — Firebase
            // requires a fresh login for destructive operations. Surfacing that is correct:
            // it tells the user to sign in again rather than silently leaving the account.
            user.delete().await()
            Log.i(TAG, "Firebase user deleted")
        }

        // Cancel alarms before dropping the rows they point at, so no alarm can fire
        // against a dose that no longer exists.
        alarmScheduler.cancel()

        db.withTransaction {
            // Cascades to schedules and doses via the foreign keys.
            medicationDao.deleteAll()
        }

        vault.forget()
        auth.signOut()
        Log.i(TAG, "Local data cleared")
    }.onFailure {
        Log.e(TAG, "Account deletion failed", it)
    }

    /**
     * Removes the user's Firestore documents.
     *
     * Firestore has no recursive delete from a client — deleting a document does NOT delete
     * its subcollections, which would silently leave every encrypted record behind under a
     * deleted parent. Each collection is enumerated and deleted explicitly.
     */
    private suspend fun deleteRemote(uid: String) {
        val userDoc = firestore.collection("users").document(uid)

        for (collection in listOf("records", "keys")) {
            val snapshot = userDoc.collection(collection).get().await()
            // Batched: hundreds of individual deletes would be slow and could leave the
            // job half-done if the network dropped midway.
            var batch = firestore.batch()
            var count = 0
            for (doc in snapshot.documents) {
                batch.delete(doc.reference)
                count++
                if (count % BATCH_LIMIT == 0) {
                    batch.commit().await()
                    batch = firestore.batch()
                }
            }
            if (count % BATCH_LIMIT != 0) batch.commit().await()
            Log.i(TAG, "Deleted $count docs from $collection")
        }

        userDoc.delete().await()
    }

    private companion object {
        const val TAG = "AccountDeleter"

        /** Firestore caps a batch at 500 writes. */
        const val BATCH_LIMIT = 400
    }
}
