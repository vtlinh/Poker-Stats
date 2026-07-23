package com.pokerstats.odds.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HandEvaluatorTest {

    private fun hand(vararg codes: String): List<Card> = codes.map { Card.parse(it) }
    private fun score(vararg codes: String): Int = HandEvaluator.evaluate(hand(*codes))
    private fun category(vararg codes: String): HandCategory =
        HandEvaluator.categoryOf(score(*codes))

    @Test
    fun detectsEachCategory() {
        assertEquals(HandCategory.STRAIGHT_FLUSH, category("As", "Ks", "Qs", "Js", "Ts"))
        assertEquals(HandCategory.FOUR_OF_A_KIND, category("As", "Ah", "Ad", "Ac", "Kd"))
        assertEquals(HandCategory.FULL_HOUSE, category("As", "Ah", "Ad", "Kc", "Kd"))
        assertEquals(HandCategory.FLUSH, category("As", "Js", "9s", "5s", "2s"))
        assertEquals(HandCategory.STRAIGHT, category("9c", "8d", "7h", "6s", "5c"))
        assertEquals(HandCategory.THREE_OF_A_KIND, category("Qs", "Qh", "Qd", "7c", "2d"))
        assertEquals(HandCategory.TWO_PAIR, category("Js", "Jh", "4d", "4c", "9d"))
        assertEquals(HandCategory.PAIR, category("Ts", "Th", "6d", "4c", "2d"))
        assertEquals(HandCategory.HIGH_CARD, category("As", "Jh", "9d", "6c", "3d"))
    }

    @Test
    fun wheelStraightIsFiveHigh() {
        assertEquals(HandCategory.STRAIGHT, category("Ah", "2c", "3d", "4s", "5h"))
        // A wheel (5-high) must lose to a six-high straight.
        assertTrue(score("6h", "2c", "3d", "4s", "5h") > score("Ah", "2c", "3d", "4s", "5h"))
        // ...and the wheel straight flush is the weakest straight flush.
        assertEquals(HandCategory.STRAIGHT_FLUSH, category("Ah", "2h", "3h", "4h", "5h"))
    }

    @Test
    fun categoryOrderingHolds() {
        val ascending = listOf(
            score("As", "Jh", "9d", "6c", "3d"),   // high card
            score("Ts", "Th", "6d", "4c", "2d"),   // pair
            score("Js", "Jh", "4d", "4c", "9d"),   // two pair
            score("Qs", "Qh", "Qd", "7c", "2d"),   // trips
            score("9c", "8d", "7h", "6s", "5c"),   // straight
            score("As", "Js", "9s", "5s", "2s"),   // flush
            score("As", "Ah", "Ad", "Kc", "Kd"),   // full house
            score("As", "Ah", "Ad", "Ac", "Kd"),   // quads
            score("As", "Ks", "Qs", "Js", "Ts"),   // straight flush
        )
        for (i in 1 until ascending.size) {
            assertTrue("index $i must beat ${i - 1}", ascending[i] > ascending[i - 1])
        }
    }

    @Test
    fun kickersBreakTies() {
        // Pair of kings, ace kicker beats pair of kings, queen kicker.
        assertTrue(
            score("Ks", "Kh", "Ad", "5c", "2d") > score("Kc", "Kd", "Qd", "5s", "2h"),
        )
        // Higher two pair wins.
        assertTrue(
            score("As", "Ah", "2d", "2c", "9d") > score("Ks", "Kh", "Qd", "Qc", "9s"),
        )
    }

    @Test
    fun picksBestFiveFromSeven() {
        // Seven cards containing a flush plus a pair -> flush is chosen.
        assertEquals(
            HandCategory.FLUSH,
            HandEvaluator.categoryOf(
                score("As", "Ks", "9s", "5s", "2s", "Kh", "Kd"),
            ),
        )
        // Two trips out of seven -> full house using the higher trips.
        assertEquals(
            HandCategory.FULL_HOUSE,
            HandEvaluator.categoryOf(
                score("As", "Ah", "Ad", "Kc", "Kd", "Kh", "2s"),
            ),
        )
    }

    /**
     * The fast direct evaluator must agree with brute force: the best of all 21
     * five-card subsets of a seven-card hand.
     */
    @Test
    fun directEvaluatorMatchesBruteForce() {
        val rng = Random(20260723)
        repeat(50_000) {
            val cards = Card.FULL_DECK.shuffled(rng).take(7)
            val direct = HandEvaluator.evaluate(cards)
            val brute = bestOfSubsets(cards)
            assertEquals("mismatch for $cards", brute, direct)
        }
    }

    private fun bestOfSubsets(cards: List<Card>): Int {
        var best = -1
        for (a in 0 until 3) for (b in a + 1 until 4) for (c in b + 1 until 5)
            for (d in c + 1 until 6) for (e in d + 1 until 7) {
                val subset = listOf(cards[a], cards[b], cards[c], cards[d], cards[e])
                val s = HandEvaluator.evaluate(subset)
                if (s > best) best = s
            }
        return best
    }
}
