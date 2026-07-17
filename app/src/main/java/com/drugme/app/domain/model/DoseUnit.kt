package com.drugme.app.domain.model

/**
 * Units a dose can be expressed in.
 *
 * Stored by [name], never by ordinal — reordering this enum must not silently
 * reinterpret existing rows as a different unit.
 */
enum class DoseUnit(val label: String, val allowsFraction: Boolean) {
    MG("mg", true),
    MCG("mcg", true),
    G("g", true),
    ML("ml", true),
    PILL("pill", true),
    TABLET("tablet", true),
    CAPSULE("capsule", false),
    DROP("drop", false),
    SPRAY("spray", false),
    PUFF("puff", false),
    IU("IU", true),
    UNIT("unit", true),
    SACHET("sachet", false),
    PATCH("patch", false),
    SUPPOSITORY("suppository", false),
    TSP("tsp", true),
    TBSP("tbsp", true);

    /**
     * Renders an amount for display, dropping the redundant ".0" on whole numbers
     * and pluralising count-like units ("2 pills", but never "2 mgs").
     */
    fun format(amount: Double): String {
        val n = if (amount % 1.0 == 0.0) amount.toLong().toString() else amount.toString()
        val plural = when (this) {
            MG, MCG, G, ML, IU, TSP, TBSP -> label
            else -> if (amount == 1.0) label else "${label}s"
        }
        return "$n $plural"
    }

    companion object {
        /** Units offered first in the picker, covering the overwhelming majority of entries. */
        val COMMON = listOf(MG, PILL, TABLET, ML, CAPSULE, DROP, SPRAY, PUFF)
    }
}
