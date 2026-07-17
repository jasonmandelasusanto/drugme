package com.drugme.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.drugme.app.domain.model.DoseUnit
import com.drugme.app.domain.model.FoodRelation
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

    /**
     * How this dose relates to eating. Surfaced on the reminder itself, since that is when
     * the user acts on it.
     */
    @ColumnInfo(defaultValue = "ANY")
    val foodRelation: FoodRelation = FoodRelation.ANY,

    /**
     * MeSH id of the condition this is taken for, e.g. "D003924".
     *
     * The user's own statement, chosen from the disease catalog — NOT derived from the
     * drug. An earlier design offered conditions from the drug's RxNorm `may_treat` list,
     * which inverts the relationship: people know what they have before they know what
     * they take, drugs are prescribed off-label, and vitamins or contraceptives have no
     * `may_treat` edge at all. Deriving it told those users their situation was invalid.
     */
    val diseaseId: String? = null,

    /**
     * Human-readable condition name, copied at selection time. Denormalised on purpose:
     * this text is shown to the user and must stay stable across catalog updates.
     */
    val diseaseName: String? = null,

    /**
     * How much is left, in the same unit as [doseUnit]. Null means the user isn't tracking
     * stock for this medication — which must stay the default, since most people don't
     * want to, and a zero would be indistinguishable from "I've run out".
     */
    val stockAmount: Double? = null,

    /**
     * Warn this many days before the stock runs out. Only meaningful when [stockAmount]
     * is set.
     *
     * Default 7: long enough to order a repeat prescription and have it arrive, which is
     * the actual thing this reminder is for.
     */
    @ColumnInfo(defaultValue = "7")
    val refillReminderDays: Int = 7,

    /** Set when a low-stock warning was last shown, so it isn't repeated every day. */
    val refillNotifiedAt: Instant? = null,

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
