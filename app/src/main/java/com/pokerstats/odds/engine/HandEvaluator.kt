package com.pokerstats.odds.engine

/** The nine poker hand categories, ordered weakest to strongest. */
enum class HandCategory(val displayName: String) {
    HIGH_CARD("High Card"),
    PAIR("Pair"),
    TWO_PAIR("Two Pair"),
    THREE_OF_A_KIND("Three of a Kind"),
    STRAIGHT("Straight"),
    FLUSH("Flush"),
    FULL_HOUSE("Full House"),
    FOUR_OF_A_KIND("Four of a Kind"),
    STRAIGHT_FLUSH("Straight Flush");

    companion object {
        fun fromOrdinal(o: Int): HandCategory = entries[o]
    }
}

/**
 * Evaluates the best five-card poker hand out of five, six, or seven cards.
 *
 * Each hand is reduced to a single comparable [Int] score: the top four bits
 * hold the [HandCategory] ordinal and the low twenty bits hold up to five rank
 * nibbles used for tie-breaking, so a plain integer comparison reproduces the
 * exact rules of poker (including kickers and the A-2-3-4-5 "wheel" straight).
 *
 * The algorithm works directly on rank/suit counts instead of enumerating all
 * C(7,5)=21 five-card subsets, keeping the Monte-Carlo simulation fast. It is
 * cross-checked against a brute-force enumerator over hundreds of thousands of
 * random hands in the unit tests.
 */
object HandEvaluator {

    private const val CATEGORY_SHIFT = 20

    /** Packed score for a list of 5..7 cards. Higher is better. */
    fun evaluate(cards: List<Card>): Int {
        require(cards.size in 5..7) { "Need 5 to 7 cards, got ${cards.size}" }
        val indices = IntArray(cards.size) { cards[it].index }
        return evaluateIndices(indices, indices.size)
    }

    /** The [HandCategory] of a packed [score]. */
    fun categoryOf(score: Int): HandCategory = HandCategory.fromOrdinal(score ushr CATEGORY_SHIFT)

    /**
     * Core evaluator operating on raw card indices (0..51) for speed. Only the
     * first [count] entries of [indices] are used.
     */
    fun evaluateIndices(indices: IntArray, count: Int): Int {
        val rankCount = IntArray(15)
        val suitCount = IntArray(4)
        // Per-suit rank bitmask, for flush / straight-flush detection.
        val suitRankMask = IntArray(4)
        var rankMask = 0

        for (i in 0 until count) {
            val idx = indices[i]
            val rank = idx / 4 + 2
            val suit = idx % 4
            rankCount[rank]++
            suitCount[suit]++
            suitRankMask[suit] = suitRankMask[suit] or (1 shl rank)
            rankMask = rankMask or (1 shl rank)
        }

        // Straight flush.
        var flushSuit = -1
        for (s in 0 until 4) {
            if (suitCount[s] >= 5) { flushSuit = s; break }
        }
        if (flushSuit >= 0) {
            val sfHigh = straightHigh(suitRankMask[flushSuit])
            if (sfHigh != 0) return pack(HandCategory.STRAIGHT_FLUSH, sfHigh)
        }

        // Group ranks by multiplicity, highest rank first.
        var quad = 0
        var tripHigh = 0
        var tripSecond = 0
        var pairHigh = 0
        var pairSecond = 0
        for (r in 14 downTo 2) {
            when (rankCount[r]) {
                4 -> if (quad == 0) quad = r
                3 -> if (tripHigh == 0) tripHigh = r else if (tripSecond == 0) tripSecond = r
                2 -> if (pairHigh == 0) pairHigh = r else if (pairSecond == 0) pairSecond = r
            }
        }

        // Four of a kind.
        if (quad != 0) {
            val kicker = highestRankExcept(rankCount, quad)
            return pack(HandCategory.FOUR_OF_A_KIND, quad, kicker)
        }

        // Full house (trips + another trips-or-pair).
        if (tripHigh != 0 && (tripSecond != 0 || pairHigh != 0)) {
            val pair = if (tripSecond != 0) tripSecond else pairHigh
            return pack(HandCategory.FULL_HOUSE, tripHigh, pair)
        }

        // Flush.
        if (flushSuit >= 0) {
            return packRanks(HandCategory.FLUSH, top5Ranks(suitRankMask[flushSuit]))
        }

        // Straight.
        val straightHigh = straightHigh(rankMask)
        if (straightHigh != 0) return pack(HandCategory.STRAIGHT, straightHigh)

        // Three of a kind.
        if (tripHigh != 0) {
            val kickers = topRanksExcept(rankCount, tripHigh, 0, 2)
            return pack(HandCategory.THREE_OF_A_KIND, tripHigh, kickers[0], kickers[1])
        }

        // Two pair.
        if (pairHigh != 0 && pairSecond != 0) {
            val kicker = highestRankExcept(rankCount, pairHigh, pairSecond)
            return pack(HandCategory.TWO_PAIR, pairHigh, pairSecond, kicker)
        }

        // One pair.
        if (pairHigh != 0) {
            val kickers = topRanksExcept(rankCount, pairHigh, 0, 3)
            return pack(HandCategory.PAIR, pairHigh, kickers[0], kickers[1], kickers[2])
        }

        // High card.
        return packRanks(HandCategory.HIGH_CARD, top5Ranks(rankMask))
    }

    /** Highest rank of a 5-card straight in [mask], or 0. Handles the wheel. */
    private fun straightHigh(mask: Int): Int {
        var m = mask
        if (m and (1 shl 14) != 0) m = m or (1 shl 1) // Ace plays low for A-2-3-4-5.
        var run = 0
        for (r in 14 downTo 1) {
            if (m and (1 shl r) != 0) {
                run++
                if (run >= 5) return r + 4
            } else {
                run = 0
            }
        }
        return 0
    }

    private fun highestRankExcept(rankCount: IntArray, vararg exclude: Int): Int {
        for (r in 14 downTo 2) {
            if (rankCount[r] > 0 && r !in exclude) return r
        }
        return 0
    }

    private fun topRanksExcept(rankCount: IntArray, exclude1: Int, exclude2: Int, howMany: Int): IntArray {
        val out = IntArray(howMany)
        var n = 0
        for (r in 14 downTo 2) {
            if (n == howMany) break
            if (rankCount[r] > 0 && r != exclude1 && r != exclude2) out[n++] = r
        }
        return out
    }

    /** Top five set bits (ranks) of [mask], highest first. */
    private fun top5Ranks(mask: Int): IntArray {
        val out = IntArray(5)
        var n = 0
        for (r in 14 downTo 2) {
            if (n == 5) break
            if (mask and (1 shl r) != 0) out[n++] = r
        }
        return out
    }

    private fun pack(category: HandCategory, vararg ranks: Int): Int {
        var v = 0
        for (r in ranks) v = v * 16 + r
        return (category.ordinal shl CATEGORY_SHIFT) + v
    }

    private fun packRanks(category: HandCategory, ranks: IntArray): Int {
        var v = 0
        for (r in ranks) v = v * 16 + r
        return (category.ordinal shl CATEGORY_SHIFT) + v
    }
}
