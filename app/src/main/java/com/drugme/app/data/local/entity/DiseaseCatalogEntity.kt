package com.drugme.app.data.local.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

/**
 * Bundled MeSH condition list — public reference data, replaced wholesale on app update.
 *
 * Separate from the drug catalog on purpose. Conditions are the user's own statement about
 * themselves, not a property of what they take: people know their diagnosis before their
 * prescription, drugs get used off-label, and vitamins or contraceptives have no listed
 * indication at all. Deriving the condition from the drug — the previous design — quietly
 * told those users their situation didn't exist.
 *
 * Not user data, so never encrypted or synced.
 */
@Entity(tableName = "disease_catalog")
data class DiseaseCatalogEntity(
    /** MeSH descriptor id, e.g. "D003924". */
    @PrimaryKey val id: String,
    val name: String,
)

/** FTS index over condition names for type-ahead. External content: rows stay in the base table. */
@Fts4(contentEntity = DiseaseCatalogEntity::class)
@Entity(tableName = "disease_catalog_fts")
data class DiseaseCatalogFts(
    val name: String,
)
