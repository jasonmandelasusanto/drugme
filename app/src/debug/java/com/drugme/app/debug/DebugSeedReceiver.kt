package com.drugme.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.drugme.app.alarm.DoseAlarmScheduler
import com.drugme.app.data.local.dao.DoseDao
import com.drugme.app.data.local.entity.DoseEntity
import com.drugme.app.data.local.entity.MedicationEntity
import com.drugme.app.data.local.entity.ScheduleEntity
import com.drugme.app.data.repo.DoseRepository
import com.drugme.app.data.repo.MedicationRepository
import com.drugme.app.domain.model.DoseStatus
import com.drugme.app.domain.model.DoseUnit
import com.drugme.app.domain.model.ScheduleType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/**
 * Debug-only harness for driving the alarm engine from adb before any UI exists.
 *
 * Lives in src/debug, so it is compiled out of release builds entirely rather than
 * relying on a runtime flag — a remotely-triggerable "insert arbitrary dose" endpoint
 * has no business existing in a shipped medication app.
 *
 *   # dose due in 30 seconds
 *   adb shell am broadcast -a com.drugme.app.debug.SEED --es name Metformin --ei secs 30
 *
 *   # inspect state
 *   adb shell am broadcast -a com.drugme.app.debug.DUMP
 */
@AndroidEntryPoint
class DebugSeedReceiver : BroadcastReceiver() {

    @Inject lateinit var medicationRepository: MedicationRepository
    @Inject lateinit var doseRepository: DoseRepository
    @Inject lateinit var doseDao: DoseDao
    @Inject lateinit var scheduler: DoseAlarmScheduler
    @Inject lateinit var clock: Clock

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    ACTION_SEED -> seed(
                        name = intent.getStringExtra("name") ?: "Test Drug",
                        secs = intent.getIntExtra("secs", 30).toLong(),
                    )
                    ACTION_DUMP -> dump()
                    ACTION_REARM -> {
                        scheduler.rescheduleNext()
                        Log.i(TAG, "Re-armed on request")
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "debug action failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun seed(name: String, secs: Long) {
        val now = clock.instant()
        val medId = UUID.randomUUID().toString()
        val schedId = UUID.randomUUID().toString()
        val fireAt = now.plusSeconds(secs)
        val zone: ZoneId = clock.zone
        // atZone().toLocalTime(), not LocalTime.ofInstant(): the latter is API 31+ and
        // minSdk here is 26, so it would crash on anything below Android 12. Same result.
        val localTime = fireAt.atZone(zone).toLocalTime().withNano(0)

        medicationRepository.save(
            medication = MedicationEntity(
                id = medId,
                name = name,
                rxcui = null,
                doseAmount = 500.0,
                doseUnit = DoseUnit.MG,
                diseaseId = "D003924",
                diseaseName = "Diabetes Mellitus, Type 2",
                createdAt = now,
                updatedAt = now,
            ),
            schedules = listOf(
                ScheduleEntity(
                    id = schedId,
                    medicationId = medId,
                    type = ScheduleType.TIMES_PER_DAY,
                    timesOfDay = listOf(localTime),
                    startDate = LocalDate.now(clock),
                    endDate = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        )

        // The schedule's own time is rounded to the second, so also insert a dose at the
        // exact requested instant to make short-horizon adb tests deterministic.
        doseDao.insertIgnore(
            listOf(
                DoseEntity(
                    id = UUID.randomUUID().toString(),
                    medicationId = medId,
                    scheduleId = schedId,
                    scheduledAt = fireAt,
                    localDate = fireAt.atZone(zone).toLocalDate(), // LocalDate.ofInstant is API 31+
                    status = DoseStatus.PENDING,
                ),
            )
        )
        scheduler.rescheduleNext()
        Log.i(TAG, "SEEDED med=$name dose at $fireAt (in ${secs}s)")
    }

    private suspend fun dump() {
        val next = doseRepository.getNextPending(Instant.EPOCH)
        Log.i(TAG, "DUMP nextPending=${next?.id} at=${next?.effectiveAt} status=${next?.status}")
        Log.i(TAG, "DUMP canScheduleExact=${scheduler.canScheduleExact()}")
    }

    private companion object {
        const val TAG = "DebugSeed"
        const val ACTION_SEED = "com.drugme.app.debug.SEED"
        const val ACTION_DUMP = "com.drugme.app.debug.DUMP"
        const val ACTION_REARM = "com.drugme.app.debug.REARM"
    }
}
