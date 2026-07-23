package com.pokerstats.odds.engine

import kotlin.random.Random

/**
 * Outcome of an equity simulation for the hero's hand.
 *
 * [win], [tie] and [lose] are fractions in `0.0..1.0` that sum to 1. [equity]
 * is the pot-share expectation (a split pot counts as a fractional win).
 */
data class EquityResult(
    val win: Double,
    val tie: Double,
    val lose: Double,
    val iterations: Int,
    val categoryFrequency: Map<HandCategory, Double>,
) {
    /** Expected pot share, where an n-way tie is worth 1/n. */
    val equity: Double get() = win + tie

    val winPercent: Double get() = win * 100.0
    val tiePercent: Double get() = tie * 100.0
    val losePercent: Double get() = lose * 100.0
    val equityPercent: Double get() = equity * 100.0
}

/**
 * Estimates the probability that a Texas Hold'em hand wins, ties, or loses at
 * showdown against a number of random opponent hands, using Monte-Carlo
 * simulation over the unknown cards.
 */
class EquityCalculator {

    /**
     * @param hole      the hero's two hole cards.
     * @param board     0, 3, 4 or 5 community cards already dealt.
     * @param opponents number of opposing players (1..9).
     * @param iterations number of random deals to simulate.
     * @param random    RNG (injectable for deterministic tests).
     * @param onProgress optional callback invoked with progress in `0f..1f`.
     */
    fun simulate(
        hole: List<Card>,
        board: List<Card> = emptyList(),
        opponents: Int = 1,
        iterations: Int = DEFAULT_ITERATIONS,
        random: Random = Random.Default,
        onProgress: ((Float) -> Unit)? = null,
    ): EquityResult {
        require(hole.size == 2) { "Hero must have exactly 2 hole cards" }
        require(board.size in intArrayOf(0, 3, 4, 5)) { "Board must have 0, 3, 4 or 5 cards" }
        require(opponents in 1..9) { "Opponents must be between 1 and 9" }
        val known = hole + board
        require(known.size == known.distinct().size) { "Duplicate cards supplied" }

        // Remaining deck as raw indices.
        val used = BooleanArray(52)
        for (c in known) used[c.index] = true
        val deck = IntArray(52 - known.size)
        run {
            var n = 0
            for (i in 0 until 52) if (!used[i]) deck[n++] = i
        }

        val boardCount = board.size
        val communityToDraw = 5 - boardCount
        val cardsToDraw = communityToDraw + opponents * 2

        // Pre-filled scratch arrays reused across iterations.
        val heroCards = IntArray(7)
        heroCards[0] = hole[0].index
        heroCards[1] = hole[1].index
        for (i in 0 until boardCount) heroCards[2 + i] = board[i].index

        val oppCards = IntArray(7)
        for (i in 0 until boardCount) oppCards[2 + i] = board[i].index

        var wins = 0
        var ties = 0
        var losses = 0
        val categoryCounts = IntArray(HandCategory.entries.size)
        val progressStep = (iterations / 100).coerceAtLeast(1)

        for (iter in 0 until iterations) {
            partialShuffle(deck, cardsToDraw, random)

            // First `communityToDraw` drawn cards complete the board.
            var draw = 0
            for (i in 0 until communityToDraw) {
                val card = deck[draw++]
                heroCards[2 + boardCount + i] = card
                oppCards[2 + boardCount + i] = card
            }

            val heroScore = HandEvaluator.evaluateIndices(heroCards, 7)
            categoryCounts[heroScore ushr 20]++

            var bestOpp = -1
            for (o in 0 until opponents) {
                oppCards[0] = deck[draw++]
                oppCards[1] = deck[draw++]
                val oppScore = HandEvaluator.evaluateIndices(oppCards, 7)
                if (oppScore > bestOpp) bestOpp = oppScore
            }

            when {
                heroScore > bestOpp -> wins++
                heroScore < bestOpp -> losses++
                // Hero shares the best score at showdown: a (possibly multi-way) tie.
                else -> ties++
            }

            if (onProgress != null && iter % progressStep == 0) {
                onProgress(iter.toFloat() / iterations)
            }
        }
        onProgress?.invoke(1f)

        val n = iterations.toDouble()
        val frequency = HandCategory.entries.associateWith { categoryCounts[it.ordinal] / n }
        return EquityResult(
            win = wins / n,
            tie = ties / n,
            lose = losses / n,
            iterations = iterations,
            categoryFrequency = frequency,
        )
    }

    /**
     * Fisher-Yates over just the first [count] positions of [deck], enough to
     * draw [count] distinct random cards without shuffling all 52 each time.
     */
    private fun partialShuffle(deck: IntArray, count: Int, random: Random) {
        for (i in 0 until count) {
            val j = i + random.nextInt(deck.size - i)
            val tmp = deck[i]
            deck[i] = deck[j]
            deck[j] = tmp
        }
    }

    companion object {
        const val DEFAULT_ITERATIONS = 100_000
    }
}
