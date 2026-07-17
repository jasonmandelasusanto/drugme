package com.drugme.app.data.local.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

/**
 * Bundled RxNorm reference data — public, read-only, replaced wholesale on app update.
 *
 * This is not user data and is never encrypted or synced. Only the user's own
 * medications carry that requirement.
 */
@Entity(tableName = "drug_catalog")
data class DrugCatalogEntity(
    @PrimaryKey val rxcui: String,
    val name: String,

    /**
     * Diseases this drug may treat, as a JSON array of {id,name}.
     *
     * Sourced exclusively from MED-RT `may_treat`. RxClass also exposes `ci_with`
     * (contraindicated-with) for the same drug — for metformin that includes acidosis
     * and liver disease. Those are conditions where the drug is dangerous, so surfacing
     * them here as indications would invert their meaning. The generator filters the
     * relation and DrugCatalogTest asserts the shipped asset contains none of them.
     */
    val diseasesJson: String,
)

/**
 * FTS index over drug names for type-ahead.
 *
 * `contentEntity` makes this an external-content table: tokens are indexed here, rows
 * still live in [DrugCatalogEntity], so the ~20k names aren't stored twice.
 */
@Fts4(contentEntity = DrugCatalogEntity::class)
@Entity(tableName = "drug_catalog_fts")
data class DrugCatalogFts(
    val name: String,
)
