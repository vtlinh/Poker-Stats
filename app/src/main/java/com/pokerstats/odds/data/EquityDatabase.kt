package com.pokerstats.odds.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.pokerstats.odds.engine.Card
import com.pokerstats.odds.engine.HandCategory
import org.json.JSONObject
import java.io.File

/** A precomputed preflop equity result looked up from the bundled database. */
data class PreflopEquity(
    val handClass: String,
    val players: Int,
    val win: Double,
    val tie: Double,
    val lose: Double,
    val winFold: Double,
    val tieFold: Double,
    val postFoldWin: Double,
    val postFoldTie: Double,
    val categoryFrequency: Map<HandCategory, Double>,
) {
    /** Win probability with ties counted as wins. */
    val winCountingTies: Double get() = win + tie

    val winPercent: Double get() = win * 100.0
    val tiePercent: Double get() = tie * 100.0
    val losePercent: Double get() = lose * 100.0
    val winCountingTiesPercent: Double get() = winCountingTies * 100.0

    /**
     * Fold-adjusted win probability (ties counted as wins): the chance of
     * winning once every opponent whose own equity is below the break-even
     * threshold 1/players folds pre-flop.
     */
    val winFoldCountingTies: Double get() = winFold + tieFold
    val winFoldCountingTiesPercent: Double get() = winFoldCountingTies * 100.0

    /**
     * Post-flop fold-adjusted win probability (ties counted as wins): opponents
     * fold weak hands both pre-flop *and* on the flop (equity below the shrunk
     * break-even 1/remaining), and the hero folds its own weak flops too, all
     * averaged over every possible flop. A hand that folds pre-flop has no
     * post-flop equity (0).
     */
    val postFoldCountingTies: Double get() = postFoldWin + postFoldTie
    val postFoldCountingTiesPercent: Double get() = postFoldCountingTies * 100.0
}

/**
 * Read-only access to the precomputed preflop-equity table shipped as an asset.
 *
 * The app performs no poker math at runtime: it copies the bundled SQLite file
 * out of assets on first use and answers every query with a single indexed
 * lookup on (hand class, player count).
 */
class EquityDatabase(private val appContext: Context) {

    @Volatile private var cached: SQLiteDatabase? = null

    private fun database(): SQLiteDatabase {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val db = open()
            cached = db
            return db
        }
    }

    private fun open(): SQLiteDatabase {
        val dbFile = appContext.getDatabasePath(ASSET_NAME)
        if (dbFile.exists() && !isCurrent(dbFile)) {
            dbFile.delete()
        }
        if (!dbFile.exists()) {
            copyFromAssets(dbFile)
        }
        return SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    private fun isCurrent(dbFile: File): Boolean = try {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA user_version", null).use { c ->
                c.moveToFirst() && c.getInt(0) == EXPECTED_VERSION
            }
        }
    } catch (e: Exception) {
        false
    }

    private fun copyFromAssets(dbFile: File) {
        dbFile.parentFile?.mkdirs()
        appContext.assets.open(ASSET_NAME).use { input ->
            dbFile.outputStream().use { output -> input.copyTo(output) }
        }
    }

    /** Look up the equity for the given two-card hand and total player count. */
    fun lookup(cardA: Card, cardB: Card, players: Int): PreflopEquity? =
        lookup(canonicalKey(cardA, cardB), players)

    fun lookup(handClass: String, players: Int): PreflopEquity? {
        val cursor = database().rawQuery(
            "SELECT win, tie, lose, win_fold, tie_fold, post_fold_win, post_fold_tie, categories " +
                "FROM equity WHERE hand_class = ? AND players = ? LIMIT 1",
            arrayOf(handClass, players.toString()),
        )
        cursor.use { c ->
            if (!c.moveToFirst()) return null
            return PreflopEquity(
                handClass = handClass,
                players = players,
                win = c.getDouble(0),
                tie = c.getDouble(1),
                lose = c.getDouble(2),
                winFold = c.getDouble(3),
                tieFold = c.getDouble(4),
                postFoldWin = c.getDouble(5),
                postFoldTie = c.getDouble(6),
                categoryFrequency = parseCategories(c.getString(7)),
            )
        }
    }

    private fun parseCategories(json: String): Map<HandCategory, Double> {
        val obj = JSONObject(json)
        val map = LinkedHashMap<HandCategory, Double>()
        for (category in HandCategory.entries) {
            if (obj.has(category.name)) map[category] = obj.getDouble(category.name)
        }
        return map
    }

    companion object {
        private const val ASSET_NAME = "poker_equity.db"
        private const val EXPECTED_VERSION = 3

        /** Canonical starting-hand key, e.g. "AA", "AKs", "AKo", "72o". */
        fun canonicalKey(a: Card, b: Card): String {
            val (hi, lo) = if (a.rank.value >= b.rank.value) a to b else b to a
            return if (hi.rank == lo.rank) {
                hi.rank.symbol + lo.rank.symbol
            } else {
                hi.rank.symbol + lo.rank.symbol + if (a.suit == b.suit) "s" else "o"
            }
        }
    }
}
