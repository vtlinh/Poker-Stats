package com.pokerstats.odds.engine

/** Card rank, from Two (2) to Ace (14). */
enum class Rank(val value: Int, val symbol: String) {
    TWO(2, "2"), THREE(3, "3"), FOUR(4, "4"), FIVE(5, "5"), SIX(6, "6"),
    SEVEN(7, "7"), EIGHT(8, "8"), NINE(9, "9"), TEN(10, "T"),
    JACK(11, "J"), QUEEN(12, "Q"), KING(13, "K"), ACE(14, "A");

    companion object {
        fun fromSymbol(s: String): Rank = entries.first { it.symbol == s }
    }
}

/** Card suit. */
enum class Suit(val symbol: String, val glyph: String) {
    CLUBS("c", "♣"),
    DIAMONDS("d", "♦"),
    HEARTS("h", "♥"),
    SPADES("s", "♠");

    val isRed: Boolean get() = this == DIAMONDS || this == HEARTS

    companion object {
        fun fromSymbol(s: String): Suit = entries.first { it.symbol == s }
    }
}

/**
 * A single playing card. Encoded compactly as an index 0..51 so decks and
 * hand evaluation stay allocation-free in the Monte Carlo hot loop.
 */
data class Card(val rank: Rank, val suit: Suit) {
    /** Stable index 0..51 = rank-offset * 4 + suit-ordinal. */
    val index: Int get() = (rank.value - 2) * 4 + suit.ordinal

    /** Short label such as "As" or "Th". */
    val code: String get() = rank.symbol + suit.symbol

    override fun toString(): String = code

    companion object {
        /** The full 52-card deck in a stable order. */
        val FULL_DECK: List<Card> = Rank.entries.flatMap { r -> Suit.entries.map { s -> Card(r, s) } }

        fun fromIndex(index: Int): Card {
            val rank = Rank.entries[index / 4]
            val suit = Suit.entries[index % 4]
            return Card(rank, suit)
        }

        /** Parse a code such as "As", "Th", "2c". */
        fun parse(code: String): Card {
            require(code.length == 2) { "Invalid card code: $code" }
            return Card(Rank.fromSymbol(code.substring(0, 1)), Suit.fromSymbol(code.substring(1, 2)))
        }
    }
}
