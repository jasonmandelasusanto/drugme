package com.drugme.app.domain.schedule

import com.drugme.app.data.local.dao.MedicationWithSchedules
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** When a medication's stock runs out, and whether that is soon enough to warn about. */
data class Forecast(
    /** Date the stock is exhausted, or null if it lasts beyond the horizon. */
    val runOutDate: LocalDate?,
    val daysRemaining: Int?,
    val dosesRemaining: Int?,
    val stockAmount: Double,
    val needsRefillWarning: Boolean,
)

/**
 * Works out when a medication runs out.
 *
 * Deliberately simulates the actual schedule rather than dividing stock by an average
 * daily dose. Averages are wrong for exactly the schedules people use them for: an
 * every-other-day drug, or one taken only on weekdays, would be reported as running out
 * far sooner than it does, and the user would refill early every single time until they
 * stopped believing the warning.
 *
 * Pure — no clock, no database — so the awkward cases are testable.
 */
@Singleton
class StockForecast @Inject constructor(
    private val generator: DoseOccurrenceGenerator,
) {

    /**
     * @param today the reference date
     * @param horizonDays how far to look ahead before giving up and reporting "lasts a long time"
     */
    fun forecast(
        item: MedicationWithSchedules,
        today: LocalDate,
        zone: ZoneId,
        horizonDays: Long = HORIZON_DAYS,
    ): Forecast? {
        val stock = item.medication.stockAmount ?: return null
        val baseDose = item.medication.doseAmount
        val baseStockUse = item.medication.stockPerDose ?: baseDose
        if (baseDose <= 0.0 || baseStockUse <= 0.0) return null

        // Every upcoming dose across all of this medication's schedules, in time order.
        val upcoming = item.schedules
            .flatMap { schedule ->
                generator.generate(schedule = schedule, from = today, to = today.plusDays(horizonDays), zone = zone)
                    .map { occurrence -> occurrence to stockUse(item, schedule, baseDose, baseStockUse) }
            }
            .sortedBy { it.first.scheduledAt }

        if (upcoming.isEmpty()) {
            // Nothing scheduled: the stock is not being consumed, so it never runs out.
            return Forecast(
                runOutDate = null,
                daysRemaining = null,
                dosesRemaining = null,
                stockAmount = stock,
                needsRefillWarning = false,
            )
        }

        var remaining = stock
        var taken = 0
        for ((occurrence, perDose) in upcoming) {
            if (remaining < perDose) {
                // This dose can't be covered — that day is when they actually run short.
                val days = java.time.temporal.ChronoUnit.DAYS.between(today, occurrence.localDate).toInt()
                return Forecast(
                    runOutDate = occurrence.localDate,
                    daysRemaining = days,
                    dosesRemaining = taken,
                    stockAmount = stock,
                    needsRefillWarning = days <= item.medication.refillReminderDays,
                )
            }
            remaining -= perDose
            taken++
        }

        // Survived the whole horizon.
        return Forecast(
            runOutDate = null,
            daysRemaining = null,
            dosesRemaining = taken,
            stockAmount = stock,
            needsRefillWarning = false,
        )
    }

    private fun stockUse(
        item: MedicationWithSchedules,
        schedule: com.drugme.app.data.local.entity.ScheduleEntity,
        baseDose: Double,
        baseStockUse: Double,
    ): Double {
        val amount = schedule.doseAmount ?: baseDose
        val unit = schedule.doseUnit ?: item.medication.doseUnit
        return if (unit == item.medication.doseUnit) {
            baseStockUse * (amount / baseDose)
        } else {
            baseStockUse
        }
    }

    private companion object {
        /**
         * A year. Long enough that "you have plenty" is true rather than an artefact of a
         * short lookahead, and cheap since occurrence generation is arithmetic.
         */
        const val HORIZON_DAYS = 365L
    }
}
