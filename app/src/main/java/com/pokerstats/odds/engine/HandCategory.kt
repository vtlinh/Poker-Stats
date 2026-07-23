package com.pokerstats.odds.engine

/**
 * The nine poker hand categories, ordered weakest to strongest.
 *
 * The `name` of each entry matches the keys used in the precomputed database's
 * category-distribution JSON (produced by `tools/equity.c`), so the two stay in
 * sync. Equities themselves are computed offline in C — the app only reads the
 * database — so no hand-evaluation code lives here anymore.
 */
enum class HandCategory(val displayName: String) {
    HIGH_CARD("High Card"),
    PAIR("Pair"),
    TWO_PAIR("Two Pair"),
    THREE_OF_A_KIND("Three of a Kind"),
    STRAIGHT("Straight"),
    FLUSH("Flush"),
    FULL_HOUSE("Full House"),
    FOUR_OF_A_KIND("Four of a Kind"),
    STRAIGHT_FLUSH("Straight Flush"),
}
