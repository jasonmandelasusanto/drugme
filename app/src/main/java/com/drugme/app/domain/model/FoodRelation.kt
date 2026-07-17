package com.drugme.app.domain.model

/**
 * How a dose relates to eating.
 *
 * Not cosmetic: for a lot of drugs this changes whether they work or whether they hurt.
 * Levothyroxine needs an empty stomach or absorption collapses; NSAIDs on an empty stomach
 * cause bleeding; metformin with food avoids GI upset. It belongs on the reminder itself,
 * because that is the moment the user acts on it.
 *
 * Stored by [name], never ordinal — reordering must not silently rewrite existing rows into
 * a different instruction.
 */
enum class FoodRelation(val label: String, val shortLabel: String) {
    /** No requirement stated. The default, because most entries genuinely have none. */
    ANY("No preference", ""),

    WITH_FOOD("With food", "with food"),
    BEFORE_FOOD("Before food", "before food"),
    AFTER_FOOD("After food", "after food"),

    /** Deliberately distinct from BEFORE_FOOD: "empty stomach" is a stricter instruction. */
    EMPTY_STOMACH("On an empty stomach", "empty stomach"),
    ;

    /** Suffix for the notification body, e.g. "500 mg · with food". Empty for [ANY]. */
    fun notificationSuffix(): String = if (this == ANY) "" else " · $shortLabel"
}
