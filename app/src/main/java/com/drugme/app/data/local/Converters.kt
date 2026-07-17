package com.drugme.app.data.local

import androidx.room.TypeConverter
import com.drugme.app.domain.model.DoseStatus
import com.drugme.app.domain.model.DoseUnit
import com.drugme.app.domain.model.FoodRelation
import com.drugme.app.domain.model.ScheduleType
import com.drugme.app.domain.model.WeekdayMask
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Room converters.
 *
 * Instants persist as epoch millis (UTC, zone-independent). LocalDate/LocalTime persist
 * as ISO text, deliberately *without* a zone — they are wall-clock values whose meaning
 * is "8am wherever the user is", and attaching a zone at rest would freeze them to the
 * zone they were created in.
 *
 * Enums persist by name, never ordinal: an ordinal mapping would silently reinterpret
 * every stored row if anyone reordered a declaration.
 */
class Converters {

    @TypeConverter
    fun instantToLong(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun longToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun localDateToString(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    fun stringToLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)

    @TypeConverter
    fun localTimeListToString(value: List<LocalTime>?): String? =
        value?.joinToString(",") { it.toString() }

    @TypeConverter
    fun stringToLocalTimeList(value: String?): List<LocalTime>? = when {
        value == null -> null
        value.isBlank() -> emptyList()
        else -> value.split(",").map { LocalTime.parse(it.trim()) }
    }

    @TypeConverter
    fun doseUnitToString(value: DoseUnit?): String? = value?.name

    @TypeConverter
    fun stringToDoseUnit(value: String?): DoseUnit? = value?.let { DoseUnit.valueOf(it) }

    @TypeConverter
    fun foodRelationToString(value: FoodRelation?): String? = value?.name

    @TypeConverter
    fun stringToFoodRelation(value: String?): FoodRelation? = value?.let {
        // Tolerate an unknown value rather than crashing: a row written by a newer version
        // must not make the app unopenable on an older one.
        runCatching { FoodRelation.valueOf(it) }.getOrDefault(FoodRelation.ANY)
    }

    @TypeConverter
    fun scheduleTypeToString(value: ScheduleType?): String? = value?.name

    @TypeConverter
    fun stringToScheduleType(value: String?): ScheduleType? = value?.let { ScheduleType.valueOf(it) }

    @TypeConverter
    fun doseStatusToString(value: DoseStatus?): String? = value?.name

    @TypeConverter
    fun stringToDoseStatus(value: String?): DoseStatus? = value?.let { DoseStatus.valueOf(it) }

    @TypeConverter
    fun weekdayMaskToInt(value: WeekdayMask?): Int? = value?.bits

    @TypeConverter
    fun intToWeekdayMask(value: Int?): WeekdayMask? = value?.let(::WeekdayMask)
}
