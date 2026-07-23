package com.pokerstats.odds.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EquityCalculatorTest {

    private val calc = EquityCalculator()
    private fun cards(vararg codes: String) = codes.map { Card.parse(it) }

    @Test
    fun probabilitiesSumToOne() {
        val r = calc.simulate(
            hole = cards("As", "Ks"),
            opponents = 2,
            iterations = 10_000,
            random = Random(1),
        )
        assertEquals(1.0, r.win + r.tie + r.lose, 1e-9)
    }

    @Test
    fun pocketAcesBeatsOneOpponentAboutEightyFivePercent() {
        val r = calc.simulate(
            hole = cards("As", "Ah"),
            opponents = 1,
            iterations = 40_000,
            random = Random(42),
        )
        // Documented heads-up equity of AA vs a random hand is ~85.2%.
        assertEquals(0.852, r.equity, 0.02)
    }

    @Test
    fun sevenTwoOffsuitIsAWeakHand() {
        val r = calc.simulate(
            hole = cards("7d", "2c"),
            opponents = 1,
            iterations = 40_000,
            random = Random(7),
        )
        // The worst starting hand: well under a coin flip against a random hand.
        assertTrue("equity was ${r.equity}", r.equity < 0.40)
    }

    @Test
    fun madeRoyalFlushAlwaysWins() {
        // Hero holds the two top spades; the board completes an unbeatable,
        // un-tie-able royal flush, so hero wins every single deal.
        val r = calc.simulate(
            hole = cards("As", "Ks"),
            board = cards("Qs", "Js", "Ts"),
            opponents = 3,
            iterations = 5_000,
            random = Random(99),
        )
        assertEquals(1.0, r.win, 1e-9)
        assertEquals(0.0, r.tie, 1e-9)
    }

    @Test
    fun moreOpponentsLowerEquity() {
        val hole = cards("As", "Ah")
        val oneOpp = calc.simulate(hole, opponents = 1, iterations = 20_000, random = Random(3)).equity
        val fiveOpp = calc.simulate(hole, opponents = 5, iterations = 20_000, random = Random(3)).equity
        assertTrue("$oneOpp should exceed $fiveOpp", oneOpp > fiveOpp)
    }
}
