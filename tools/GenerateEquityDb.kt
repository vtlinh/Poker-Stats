/*
 * Standalone generator for the precomputed preflop-equity table.
 *
 * It reuses the app's poker engine to Monte-Carlo the win/tie/lose equity and
 * final-hand-category distribution for every one of the 169 canonical Texas
 * Hold'em starting hands, against 1..5 opponents (total players 2..6), and
 * writes a tab-separated file `equity.tsv`. `build_equity_db.py` then turns
 * that into the SQLite asset shipped at app/src/main/assets/poker_equity.db.
 *
 * This is NOT part of the Gradle build — the DB is generated once and committed.
 * See tools/README.md for how to run it.
 */
import com.pokerstats.odds.engine.*
import kotlin.random.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors

fun main() {
    val order = Rank.entries.sortedByDescending { it.value } // ACE..TWO
    data class Cell(val key: String, val hole: List<Card>)
    val cells = mutableListOf<Cell>()
    for (a in 0 until 13) {
        for (b in a until 13) {
            val hi = order[a]; val lo = order[b]
            if (a == b) {
                cells += Cell(hi.symbol + hi.symbol, listOf(Card(hi, Suit.CLUBS), Card(hi, Suit.DIAMONDS)))
            } else {
                cells += Cell(hi.symbol + lo.symbol + "s", listOf(Card(hi, Suit.CLUBS), Card(lo, Suit.CLUBS)))
                cells += Cell(hi.symbol + lo.symbol + "o", listOf(Card(hi, Suit.CLUBS), Card(lo, Suit.DIAMONDS)))
            }
        }
    }
    require(cells.size == 169) { "expected 169 starting hands, got ${cells.size}" }

    val players = 2..6
    val iterations = 300_000
    val calc = EquityCalculator()
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    data class Row(val key: String, val players: Int, val win: Double, val tie: Double, val lose: Double, val catJson: String)

    val tasks = mutableListOf<Callable<Row>>()
    var seed = 1L
    for (cell in cells) {
        for (p in players) {
            val s = seed++
            tasks += Callable {
                val r = calc.simulate(cell.hole, emptyList(), p - 1, iterations, Random(s))
                val cats = HandCategory.entries.joinToString(",", "{", "}") { c ->
                    "\"${c.name}\":${"%.5f".format(r.categoryFrequency[c] ?: 0.0)}"
                }
                Row(cell.key, p, r.win, r.tie, r.lose, cats)
            }
        }
    }
    val results = pool.invokeAll(tasks).map { it.get() }
    pool.shutdown()

    val sb = StringBuilder()
    for (row in results.sortedWith(compareBy({ it.key }, { it.players }))) {
        sb.append(row.key).append('\t').append(row.players).append('\t')
            .append("%.5f".format(row.win)).append('\t')
            .append("%.5f".format(row.tie)).append('\t')
            .append("%.5f".format(row.lose)).append('\t')
            .append(row.catJson).append('\n')
    }
    java.io.File("equity.tsv").writeText(sb.toString())
    System.err.println("wrote ${results.size} rows to equity.tsv")
}
