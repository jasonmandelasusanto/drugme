package com.drugme.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.drugme.app.domain.model.DoseUnit
import java.time.Instant

/**
 * A medication the user has chosen to track.
 *
 * [rxcui] and the disease fields are denormalised copies of the bundled catalog rather
 * than foreign keys into it. The catalog ships with the APK and is replaced wholesale on
 * update; a user's record must not change meaning — or break — because a later RxNorm
 * release renamed or dropped a concept. [name] is free text and always authoritative,
 * so a drug absent from RxNorm is a first-class entry, not a degraded one.
 */
@Entity(
    tableName = "medications",
    indices = [Index("isActive")],
)
data class MedicationEntity(
    @PrimaryKey val id: String,

    val name: String,

    /** RxNorm concept id, when the user picked from the catalog. Null for free-text entries. */
    val rxcui: String? = null,

    val doseAmount: Double,
    val doseUnit: DoseUnit,

    /** MeSH id of the condition being treated, e.g. "D003924". */
    val diseaseId: String? = null,

    /**
     * Human-readable condition name, copied at selection time. Denormalised on purpose:
     * this text is shown to the user and must stay stable across catalog updates.
     */
    val diseaseName: String? = null,

    val notes: String? = null,

    /**
     * Soft-delete / pause flag. Inactive medications keep their dose history so past
     * adherence stays truthful; only future dose generation stops.
     */
    @ColumnInfo(defaultValue = "1")
    val isActive: Boolean = true,

    val createdAt: Instant,
    val updatedAt: Instant,
)
