package com.drugme.app.domain.model

import kotlinx.serialization.Serializable

/**
 * A condition a drug may treat, as asserted by MED-RT `may_treat`.
 *
 * [id] is a MeSH descriptor id (e.g. "D003924" = Diabetes Mellitus, Type 2).
 *
 * These are *indications from a reference vocabulary*, not recommendations, and the UI
 * must present them that way. The catalog generator carries the matching guarantee on the
 * other side: `ci_with` (contraindicated-with) relations are excluded, so nothing in here
 * is a condition where the drug is dangerous.
 */
@Serializable
data class DiseaseRef(
    val id: String,
    val name: String,
)
